package greenhouse.db.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import greenhouse.db.TestSupport;
import greenhouse.db.config.Config;
import greenhouse.db.mqtt.IngestService;
import greenhouse.db.mqtt.IngestStats;
import greenhouse.db.store.AlarmRepo;
import greenhouse.db.store.Database;
import greenhouse.db.store.DeviceRepo;
import greenhouse.db.store.StatusRepo;
import greenhouse.db.store.TelemetryRepo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 接口层：随机端口 + 真 HttpClient，覆盖鉴权、参数校验、单位换算与分页。 */
class ApiTest {

    private static final String TOKEN = "test-token";

    private Database db;
    private HttpApi http;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        db = TestSupport.newDatabase();
        DeviceRepo devices = new DeviceRepo(db);
        TelemetryRepo telemetry = new TelemetryRepo(db);
        AlarmRepo alarms = new AlarmRepo(db);
        StatusRepo statuses = new StatusRepo(db);
        IngestStats stats = new IngestStats();
        IngestService ingest = new IngestService(db, devices, telemetry, alarms, statuses, stats);

        Config config = Config.defaults();
        Routes routes = new Routes(db, devices, telemetry, alarms, statuses,
                config.apiTokens(), stats, () -> true);
        http = new HttpApi(config, routes);
        http.start();
        port = http.port();
        client = HttpClient.newHttpClient();

        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"online\":true,\"up\":3}"), false);
        ingest.onMessage("gh/GH-0001/telemetry", TestSupport.ascii(
                "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":0,\"up\":10,\"t\":2350,\"ah\":5680,\"sh\":3120,"
                        + "\"lux\":812,\"water\":1,\"fan\":0,\"light\":1,\"heat\":0,\"co2\":420}"), false);
        ingest.onMessage("gh/GH-0001/telemetry", TestSupport.ascii(
                "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":1,\"up\":70,\"t\":2400,\"ah\":5700,\"sh\":3100,"
                        + "\"lux\":900,\"water\":0,\"fan\":0,\"light\":1,\"heat\":0}"), false);
        ingest.onMessage("gh/GH-0001/alarm/water_lack",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"active\":true}"), false);
    }

    @AfterEach
    void tearDown() {
        http.close();
        db.close();
    }

    @Test
    void healthIsPublic() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", null);
        assertEquals(200, response.statusCode());

        JsonObject body = json(response);
        assertEquals("ok", body.get("status").getAsString());
        assertTrue(body.get("db").getAsBoolean());
        assertTrue(body.get("mqttConnected").getAsBoolean());
        assertEquals(2, body.getAsJsonObject("stats").get("inserted").getAsLong());
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/v1/devices", null);
        assertEquals(401, response.statusCode());
        assertEquals("unauthorized", json(response).get("error").getAsString());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertEquals(401, get("/api/v1/devices", "not-the-token").statusCode());
    }

    @Test
    void devicesListsDeviceWithStatus() throws Exception {
        JsonObject body = json(get("/api/v1/devices", TOKEN));

        assertEquals(1, body.get("count").getAsInt());
        JsonObject device = body.getAsJsonArray("devices").get(0).getAsJsonObject();
        assertEquals("GH-0001", device.get("deviceId").getAsString());
        assertTrue(device.get("online").getAsBoolean());
        assertNotNull(device.get("lastStatusTs"));
        assertEquals(1, device.get("seq").getAsLong());
        assertEquals(24.0, device.getAsJsonObject("values").get("tempC").getAsDouble(), 1e-9);
    }

    @Test
    void latestScalesUnitsAndListsAlarms() throws Exception {
        JsonObject body = json(get("/api/v1/devices/GH-0001/latest", TOKEN));

        assertEquals("GH-0001", body.get("deviceId").getAsString());
        assertTrue(body.get("online").getAsBoolean());
        assertEquals(1, body.get("seq").getAsLong());
        assertEquals(70, body.get("up").getAsLong());

        JsonObject values = body.getAsJsonObject("values");
        assertEquals(24.0, values.get("tempC").getAsDouble(), 1e-9);
        assertEquals(57.0, values.get("ahumPct").getAsDouble(), 1e-9);
        assertEquals(900, values.get("lux").getAsInt());
        assertFalse(values.get("water").getAsBoolean());

        JsonArray alarms = body.getAsJsonArray("alarms");
        assertEquals(1, alarms.size());
        assertEquals("water_lack", alarms.get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test
    void latestExposesUnknownFieldsAsExtra() throws Exception {
        // 第一条带 co2，最新一条没有；extra 按最新一条给出。
        JsonObject body = json(get("/api/v1/devices/GH-0001/telemetry?limit=1", TOKEN));
        JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(0, item.get("seq").getAsLong());
        assertEquals(420, item.getAsJsonObject("extra").get("co2").getAsInt());
        assertEquals(23.5, item.getAsJsonObject("values").get("tempC").getAsDouble(), 1e-9);
    }

    @Test
    void telemetryHistoryIsPaged() throws Exception {
        JsonObject first = json(get("/api/v1/devices/GH-0001/telemetry?limit=1", TOKEN));
        assertEquals(1, first.get("count").getAsInt());
        JsonObject firstItem = first.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(0, firstItem.get("seq").getAsLong());
        // 客户端要靠 b/seq 与实时消息去重（PROTOCOL 附录 B）
        assertEquals("7f3a91c4", firstItem.get("b").getAsString());
        long cursor = first.get("nextCursor").getAsLong();

        JsonObject second = json(get(
                "/api/v1/devices/GH-0001/telemetry?limit=1&cursor=" + cursor, TOKEN));
        assertEquals(1, second.get("count").getAsInt());
        assertEquals(1, second.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("seq").getAsLong());
        assertTrue(second.get("nextCursor").isJsonNull());
    }

    @Test
    void telemetryDefaultsToLast24Hours() throws Exception {
        JsonObject body = json(get("/api/v1/devices/GH-0001/telemetry", TOKEN));
        assertEquals(2, body.get("count").getAsInt());
        assertTrue(body.get("from").getAsLong() < body.get("to").getAsLong());
    }

    @Test
    void telemetryAcceptsIsoTime() throws Exception {
        JsonObject body = json(get(
                "/api/v1/devices/GH-0001/telemetry?from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z",
                TOKEN));
        assertEquals(0, body.get("count").getAsInt());
    }

    @Test
    void alarmHistoryIsReturned() throws Exception {
        JsonObject body = json(get("/api/v1/devices/GH-0001/alarms", TOKEN));
        assertEquals(1, body.get("count").getAsInt());
        JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("water_lack", item.get("code").getAsString());
        assertTrue(item.get("active").getAsBoolean());
        assertEquals("7f3a91c4", item.get("b").getAsString());
    }

    @Test
    void statusHistoryIsReturned() throws Exception {
        JsonObject body = json(get("/api/v1/devices/GH-0001/status", TOKEN));
        assertEquals(1, body.get("count").getAsInt());
        assertTrue(body.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("online").getAsBoolean());
    }

    @Test
    void unknownDeviceIs404() throws Exception {
        HttpResponse<String> response = get("/api/v1/devices/GH-9999/latest", TOKEN);
        assertEquals(404, response.statusCode());
        assertEquals("device_not_found", json(response).get("error").getAsString());
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals(404, get("/api/v1/nope", TOKEN).statusCode());
    }

    @Test
    void invalidLimitIs400() throws Exception {
        assertEquals(400, get("/api/v1/devices/GH-0001/telemetry?limit=0", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/devices/GH-0001/telemetry?limit=abc", TOKEN).statusCode());
        assertEquals(400, get("/api/v1/devices/GH-0001/telemetry?limit=99999", TOKEN).statusCode());
    }

    @Test
    void invertedWindowIs400() throws Exception {
        assertEquals(400, get(
                "/api/v1/devices/GH-0001/telemetry?from=2000&to=1000", TOKEN).statusCode());
    }

    @Test
    void invalidTimeIs400() throws Exception {
        assertEquals(400, get(
                "/api/v1/devices/GH-0001/telemetry?from=yesterday", TOKEN).statusCode());
    }

    @Test
    void nonGetIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/devices"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(405, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
