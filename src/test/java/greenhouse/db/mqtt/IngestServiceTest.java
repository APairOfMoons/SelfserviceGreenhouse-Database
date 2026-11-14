package greenhouse.db.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import greenhouse.db.model.DeviceRow;
import greenhouse.db.model.TelemetryRow;
import greenhouse.db.store.AlarmRepo;
import greenhouse.db.store.Database;
import greenhouse.db.store.DeviceRepo;
import greenhouse.db.store.StatusRepo;
import greenhouse.db.store.TelemetryRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 端到端入库：直接调 {@link IngestService#onMessage}，不需要真 broker。
 * 覆盖测试计划里的"重复报文、retained 不写事件、重启换 b、缺 b 拒收、坏报文不影响后续"。
 */
class IngestServiceTest {

    private Database db;
    private DeviceRepo devices;
    private TelemetryRepo telemetry;
    private AlarmRepo alarms;
    private IngestStats stats;
    private IngestService ingest;

    @BeforeEach
    void setUp() {
        db = TestSupport.newDatabase();
        devices = new DeviceRepo(db);
        telemetry = new TelemetryRepo(db);
        alarms = new AlarmRepo(db);
        StatusRepo statuses = new StatusRepo(db);
        stats = new IngestStats();
        ingest = new IngestService(db, devices, telemetry, alarms, statuses, stats);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void telemetryIsStoredAndDeviceUpdated() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":0,\"up\":10,\"t\":2350}"), false);

        assertEquals(1, TestSupport.count(db, "telemetry"));
        assertEquals(1, stats.insertedCount());
        assertEquals(0, stats.duplicateCount());
        assertEquals(0, stats.badPayloadCount());

        DeviceRow device = devices.find("GH-0001").orElseThrow();
        assertEquals("7f3a91c4", device.curB());
        assertEquals(0L, device.lastSeq());
    }

    @Test
    void duplicateTelemetryIsCountedNotStored() {
        byte[] payload = TestSupport.ascii(
                "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":7,\"up\":10,\"t\":2350}");

        ingest.onMessage("gh/GH-0001/telemetry", payload, false);
        ingest.onMessage("gh/GH-0001/telemetry", payload, false);

        assertEquals(1, TestSupport.count(db, "telemetry"));
        assertEquals(1, stats.insertedCount());
        assertEquals(1, stats.duplicateCount());
    }

    @Test
    void sessionChangeResetsSeqWithoutCollision() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":99,\"t\":2350}"), false);
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"1c04be77\",\"seq\":0,\"t\":2400}"), false);

        assertEquals(2, TestSupport.count(db, "telemetry"));
        assertEquals("1c04be77", devices.find("GH-0001").orElseThrow().curB());
    }

    @Test
    void uppercaseBIsNormalized() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7F3A91C4\",\"seq\":0,\"t\":2350}"), false);

        TelemetryRow row = telemetry.latest("GH-0001").orElseThrow();
        assertEquals("7f3a91c4", row.b());
    }

    @Test
    void missingBIsRejectedButLaterMessagesStillWork() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"seq\":0,\"t\":2350}"), false);

        assertEquals(0, TestSupport.count(db, "telemetry"));
        assertEquals(1, stats.badPayloadCount());

        // 关键：坏报文之后同一主题的正常报文必须照常入库（不能被 QoS 重投卡住）。
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":1,\"t\":2350}"), false);
        assertEquals(1, TestSupport.count(db, "telemetry"));
    }

    @Test
    void illegalBIsRejected() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"zzzz\",\"seq\":0,\"t\":2350}"), false);

        assertEquals(0, TestSupport.count(db, "telemetry"));
        assertEquals(1, stats.badPayloadCount());
    }

    @Test
    void unknownTopicIsCountedAsBad() {
        ingest.onMessage("gh/GH-0001/config", TestSupport.ascii("{}"), false);

        assertEquals(1, stats.badPayloadCount());
        assertEquals(0, stats.receivedCount());
    }

    @Test
    void retainedStatusDoesNotWriteEvent() {
        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"online\":true,\"up\":3}"), true);

        assertEquals(0, TestSupport.count(db, "status_event"));
        assertTrue(devices.find("GH-0001").orElseThrow().online());
        assertEquals(1, stats.retainedCount());
    }

    @Test
    void statusChangeWritesEvent() {
        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"online\":true}"), false);

        assertEquals(1, TestSupport.count(db, "status_event"));
        assertTrue(devices.find("GH-0001").orElseThrow().online());

        // LWT：没有 b，仍然要落库
        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"v\":2,\"online\":false}"), false);

        assertEquals(2, TestSupport.count(db, "status_event"));
        DeviceRow device = devices.find("GH-0001").orElseThrow();
        assertFalse(device.online());
        assertNotNull(device.lastStatusTs());
        // 会话号不能被 LWT 抹掉
        assertEquals("7f3a91c4", device.curB());
    }

    @Test
    void repeatedStatusOnlyUpdatesTimestamp() {
        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"online\":true}"), false);
        ingest.onMessage("gh/GH-0001/status",
                TestSupport.ascii("{\"online\":true}"), false);

        assertEquals(1, TestSupport.count(db, "status_event"));
        assertEquals(1, TestSupport.count(db, "device"));
    }

    @Test
    void retainedAlarmDoesNotWriteEvent() {
        ingest.onMessage("gh/GH-0001/alarm/water_lack",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"active\":true}"), true);

        assertEquals(0, TestSupport.count(db, "alarm_event"));
        assertEquals(1, alarms.listActive("GH-0001").size());
    }

    @Test
    void alarmWithoutBIsRejected() {
        ingest.onMessage("gh/GH-0001/alarm/water_lack",
                TestSupport.ascii("{\"v\":2,\"active\":true}"), false);

        assertEquals(0, TestSupport.count(db, "alarm_state"));
        assertEquals(1, stats.badPayloadCount());
    }

    @Test
    void badJsonDoesNotBreakFollowingMessages() {
        ingest.onMessage("gh/GH-0001/telemetry", TestSupport.ascii("oops"), false);
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":0}"), false);

        assertEquals(1, stats.badPayloadCount());
        assertEquals(1, TestSupport.count(db, "telemetry"));
    }

    @Test
    void unknownFieldsDoNotPreventStorage() {
        ingest.onMessage("gh/GH-0001/telemetry",
                TestSupport.ascii("{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":0,\"co2\":420,\"t\":2350}"), false);

        TelemetryRow row = telemetry.latest("GH-0001").orElseThrow();
        assertTrue(row.rawJson().contains("co2"));
        assertNull(row.ahumP100());
    }
}
