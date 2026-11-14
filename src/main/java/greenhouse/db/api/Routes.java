package greenhouse.db.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import greenhouse.db.model.AlarmEventRow;
import greenhouse.db.model.AlarmStateRow;
import greenhouse.db.model.DeviceRow;
import greenhouse.db.model.StatusEventRow;
import greenhouse.db.model.TelemetryRow;
import greenhouse.db.mqtt.IngestStats;
import greenhouse.db.store.AlarmRepo;
import greenhouse.db.store.Database;
import greenhouse.db.store.DeviceRepo;
import greenhouse.db.store.StatusRepo;
import greenhouse.db.store.TelemetryRepo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 路由、鉴权、参数校验与响应组装。
 * <p>
 * 全部只读 GET。单位换算与字段改名在 {@link Json} 里，这里只负责把行拼成结构。
 */
public final class Routes implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(Routes.class.getName());

    private static final String PREFIX = "/api/v1";
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5000;
    private static final long DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000;

    private final Database db;
    private final DeviceRepo devices;
    private final TelemetryRepo telemetry;
    private final AlarmRepo alarms;
    private final StatusRepo statuses;
    private final Set<String> tokens;
    private final IngestStats stats;
    private final Supplier<Boolean> mqttConnected;
    private final long startedAt;

    public Routes(Database db, DeviceRepo devices, TelemetryRepo telemetry, AlarmRepo alarms,
                  StatusRepo statuses, List<String> tokens, IngestStats stats,
                  Supplier<Boolean> mqttConnected) {
        this.db = db;
        this.devices = devices;
        this.telemetry = telemetry;
        this.alarms = alarms;
        this.statuses = statuses;
        this.tokens = Set.copyOf(tokens);
        this.stats = stats;
        this.mqttConnected = mqttConnected;
        this.startedAt = System.currentTimeMillis();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = "";
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new ApiException(405, "method_not_allowed", "只支持 GET");
            }
            URI uri = exchange.getRequestURI();
            path = uri.getPath() == null ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            Map<String, String> query = parseQuery(uri.getRawQuery());

            if (path.equals("/health") || path.equals(PREFIX + "/health")) {
                boolean dbOk = checkDatabase();
                send(exchange, dbOk ? 200 : 503, healthBody(dbOk));
                return;
            }

            if (!path.startsWith(PREFIX)) {
                throw new ApiException(404, "not_found", "没有这个路径: " + path);
            }
            authenticate(exchange);

            String rest = path.substring(PREFIX.length());
            if (rest.equals("/devices")) {
                send(exchange, 200, devicesBody());
                return;
            }

            String[] parts = rest.split("/", -1);
            if (parts.length == 4 && "devices".equals(parts[1]) && !parts[2].isEmpty()) {
                String deviceId = parts[2];
                String action = parts[3];
                switch (action) {
                    case "latest" -> send(exchange, 200, latestBody(deviceId));
                    case "telemetry" -> send(exchange, 200, telemetryBody(deviceId, query));
                    case "alarms" -> send(exchange, 200, alarmsBody(deviceId, query));
                    case "status" -> send(exchange, 200, statusBody(deviceId, query));
                    default -> throw new ApiException(404, "not_found", "没有这个端点: " + path);
                }
                return;
            }
            throw new ApiException(404, "not_found", "没有这个路径: " + path);

        } catch (ApiException e) {
            sendError(exchange, e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "处理请求失败: " + path, e);
            try {
                sendError(exchange, 500, "internal_error", "服务内部错误");
            } catch (IOException ignored) {
                // 响应已开始写出，无能为力
            }
        } finally {
            exchange.close();
        }
    }

    // ---------- 端点 ----------

    private JsonObject devicesBody() {
        JsonArray array = new JsonArray();
        for (DeviceRow device : devices.list()) {
            JsonObject item = new JsonObject();
            item.addProperty("deviceId", device.deviceId());
            if (device.online() != null) {
                item.addProperty("online", device.online());
            }
            addNullable(item, "lastStatusTs", device.lastStatusTs());
            item.addProperty("lastSeenTs", device.lastSeenTs());
            addNullable(item, "lastTelemetryTs", device.lastTelemetryTs());
            telemetry.latest(device.deviceId()).ifPresent(row -> {
                item.addProperty("recvTs", row.recvTs());
                item.addProperty("b", row.b());
                item.addProperty("seq", row.seq());
                if (row.up() != null) {
                    item.addProperty("up", row.up());
                }
                item.add("values", Json.values(row));
            });
            array.add(item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", array.size());
        root.add("devices", array);
        return root;
    }

    private JsonObject latestBody(String deviceId) {
        DeviceRow device = requireDevice(deviceId);
        JsonObject root = new JsonObject();
        root.addProperty("deviceId", deviceId);
        if (device.online() != null) {
            root.addProperty("online", device.online());
        }
        addNullable(root, "lastStatusTs", device.lastStatusTs());

        telemetry.latest(deviceId).ifPresent(row -> {
            root.addProperty("recvTs", row.recvTs());
            root.addProperty("b", row.b());
            root.addProperty("seq", row.seq());
            if (row.up() != null) {
                root.addProperty("up", row.up());
            }
            if (row.proto() != null) {
                root.addProperty("proto", row.proto());
            }
            root.add("values", Json.values(row));
            JsonObject extra = Json.extra(row);
            if (!extra.isEmpty()) {
                root.add("extra", extra);
            }
        });

        JsonArray activeAlarms = new JsonArray();
        for (AlarmStateRow alarm : alarms.listActive(deviceId)) {
            JsonObject item = new JsonObject();
            item.addProperty("code", alarm.code());
            item.addProperty("active", alarm.active());
            item.addProperty("firstTs", alarm.firstTs());
            item.addProperty("lastTs", alarm.lastTs());
            if (alarm.b() != null) {
                item.addProperty("b", alarm.b());
            }
            activeAlarms.add(item);
        }
        root.add("alarms", activeAlarms);
        return root;
    }

    private JsonObject telemetryBody(String deviceId, Map<String, String> query) {
        requireDevice(deviceId);
        Window window = window(query);
        List<TelemetryRow> rows = telemetry.page(
                deviceId, window.from, window.to, window.cursor, window.limit + 1);
        boolean hasMore = rows.size() > window.limit;
        if (hasMore) {
            rows = rows.subList(0, window.limit);
        }

        JsonArray items = new JsonArray();
        for (TelemetryRow row : rows) {
            items.add(Json.telemetryItem(row));
        }
        JsonObject root = pageRoot(deviceId, window, items);
        if (hasMore && !rows.isEmpty()) {
            root.addProperty("nextCursor", rows.get(rows.size() - 1).id());
        } else {
            root.add("nextCursor", JsonNull.INSTANCE);
        }
        return root;
    }

    private JsonObject alarmsBody(String deviceId, Map<String, String> query) {
        requireDevice(deviceId);
        Window window = window(query);
        List<AlarmEventRow> rows = alarms.page(
                deviceId, window.from, window.to, window.cursor, window.limit + 1);
        boolean hasMore = rows.size() > window.limit;
        if (hasMore) {
            rows = rows.subList(0, window.limit);
        }

        JsonArray items = new JsonArray();
        for (AlarmEventRow row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("recvTs", row.recvTs());
            item.addProperty("code", row.code());
            item.addProperty("active", row.active());
            if (row.b() != null) {
                item.addProperty("b", row.b());
            }
            items.add(item);
        }
        JsonObject root = pageRoot(deviceId, window, items);
        if (hasMore && !rows.isEmpty()) {
            root.addProperty("nextCursor", rows.get(rows.size() - 1).id());
        } else {
            root.add("nextCursor", JsonNull.INSTANCE);
        }
        return root;
    }

    private JsonObject statusBody(String deviceId, Map<String, String> query) {
        requireDevice(deviceId);
        Window window = window(query);
        List<StatusEventRow> rows = statuses.page(
                deviceId, window.from, window.to, window.cursor, window.limit + 1);
        boolean hasMore = rows.size() > window.limit;
        if (hasMore) {
            rows = rows.subList(0, window.limit);
        }

        JsonArray items = new JsonArray();
        for (StatusEventRow row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("recvTs", row.recvTs());
            item.addProperty("online", row.online());
            if (row.b() != null) {
                item.addProperty("b", row.b());
            }
            if (row.up() != null) {
                item.addProperty("up", row.up());
            }
            items.add(item);
        }
        JsonObject root = pageRoot(deviceId, window, items);
        if (hasMore && !rows.isEmpty()) {
            root.addProperty("nextCursor", rows.get(rows.size() - 1).id());
        } else {
            root.add("nextCursor", JsonNull.INSTANCE);
        }
        return root;
    }

    private JsonObject healthBody(boolean dbOk) {
        JsonObject root = new JsonObject();
        root.addProperty("status", dbOk ? "ok" : "degraded");
        root.addProperty("db", dbOk);
        root.addProperty("mqttConnected", Boolean.TRUE.equals(mqttConnected.get()));
        root.addProperty("uptimeMs", System.currentTimeMillis() - startedAt);

        JsonObject counters = new JsonObject();
        counters.addProperty("received", stats.receivedCount());
        counters.addProperty("inserted", stats.insertedCount());
        counters.addProperty("duplicates", stats.duplicateCount());
        counters.addProperty("badPayloads", stats.badPayloadCount());
        counters.addProperty("retained", stats.retainedCount());
        counters.addProperty("alarmEvents", stats.alarmEventCount());
        counters.addProperty("statusEvents", stats.statusEventCount());
        root.add("stats", counters);
        return root;
    }

    // ---------- 参数与工具 ----------

    private DeviceRow requireDevice(String deviceId) {
        return devices.find(deviceId).orElseThrow(
                () -> new ApiException(404, "device_not_found", "没有这个设备: " + deviceId));
    }

    private static JsonObject pageRoot(String deviceId, Window window, JsonArray items) {
        JsonObject root = new JsonObject();
        root.addProperty("deviceId", deviceId);
        root.addProperty("count", items.size());
        root.addProperty("from", window.from);
        root.addProperty("to", window.to);
        root.add("items", items);
        return root;
    }

    private record Window(long from, long to, int limit, Long cursor) {
    }

    private static Window window(Map<String, String> query) {
        long now = System.currentTimeMillis();
        long to = TimeParam.parse(query.get("to"), now);
        long from = TimeParam.parse(query.get("from"), to - DEFAULT_WINDOW_MS);
        if (from > to) {
            throw new ApiException(400, "bad_request", "from 不能晚于 to");
        }
        return new Window(from, to, parseLimit(query.get("limit")), parseCursor(query.get("cursor")));
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "bad_request", "limit 不是整数: " + raw);
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw new ApiException(400, "bad_request", "limit 必须在 1.." + MAX_LIMIT + " 之间: " + value);
        }
        return value;
    }

    private static Long parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new ApiException(400, "bad_request", "cursor 不能为负: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(400, "bad_request", "cursor 不是整数: " + raw);
        }
    }

    private boolean checkDatabase() {
        try {
            return db.tx(conn -> {
                try (var st = conn.createStatement();
                     var rs = st.executeQuery("SELECT 1")) {
                    return rs.next();
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "健康检查失败: {0}", e.getMessage());
            return false;
        }
    }

    private void authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(401, "unauthorized", "缺少 Bearer token");
        }
        String token = header.substring(7).trim();
        if (!tokens.contains(token)) {
            throw new ApiException(401, "unauthorized", "token 无效");
        }
    }

    private static void addNullable(JsonObject target, String key, Long value) {
        if (value != null) {
            target.addProperty(key, value);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_request", "查询参数编码非法: " + value);
        }
    }

    private void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", code);
        body.addProperty("message", message);
        if (status == 401) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        }
        send(exchange, status, body);
    }
}
