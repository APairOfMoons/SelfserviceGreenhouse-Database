package greenhouse.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import greenhouse.db.model.DeviceRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceRepoTest {

    private Database db;
    private DeviceRepo repo;

    @BeforeEach
    void setUp() {
        db = TestSupport.newDatabase();
        repo = new DeviceRepo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void normalizeAcceptsLowerHex() {
        assertEquals("7f3a91c4", DeviceRepo.normalizeB("7f3a91c4"));
        assertEquals("1c04be77", DeviceRepo.normalizeB("1C04BE77"));
        assertEquals("0", DeviceRepo.normalizeB("0"));
        assertEquals("abcdef1234567890", DeviceRepo.normalizeB("ABCDEF1234567890"));
    }

    @Test
    void normalizeRejectsIllegalValues() {
        assertNull(DeviceRepo.normalizeB(null));
        assertNull(DeviceRepo.normalizeB(""));
        assertNull(DeviceRepo.normalizeB("   "));
        assertNull(DeviceRepo.normalizeB("xyz"));
        assertNull(DeviceRepo.normalizeB("7f3a-91c4"));
        // 17 位超出列宽
        assertNull(DeviceRepo.normalizeB("abcdef1234567890a"));
    }

    @Test
    void ensureIsIdempotentAndKeepsFirstSeen() {
        db.tx(conn -> {
            repo.ensure(conn, "GH-0001", 1000, 2);
            return null;
        });
        db.tx(conn -> {
            repo.ensure(conn, "GH-0001", 2000, null);
            return null;
        });

        assertEquals(1, TestSupport.count(db, "device"));
        DeviceRow row = repo.find("GH-0001").orElseThrow();
        assertEquals(1000, row.firstSeenTs());
        assertEquals(2000, row.lastSeenTs());
        assertEquals(2, row.proto());
        assertNull(row.online());
    }

    @Test
    void setOnlineSurvivesSessionlessLwt() {
        db.tx(conn -> {
            repo.ensure(conn, "GH-0001", 1000, 2);
            repo.setOnline(conn, "GH-0001", 1500, true, "7f3a91c4");
            return null;
        });
        db.tx(conn -> {
            repo.setOnline(conn, "GH-0001", 2500, false, null);
            return null;
        });

        DeviceRow row = repo.find("GH-0001").orElseThrow();
        assertFalse(row.online());
        assertEquals(2500, row.lastStatusTs());
        // LWT 没有 b，不能把已有会话号抹掉
        assertEquals("7f3a91c4", row.curB());
    }

    @Test
    void markTelemetryUpdatesSnapshot() {
        db.tx(conn -> {
            repo.ensure(conn, "GH-0001", 1000, 2);
            repo.markTelemetry(conn, "GH-0001", 1000, "7f3a91c4", 86400L, 1024L);
            return null;
        });

        DeviceRow row = repo.find("GH-0001").orElseThrow();
        assertEquals("7f3a91c4", row.curB());
        assertEquals(1024L, row.lastSeq());
        assertEquals(86400L, row.lastUp());
        assertEquals(1000, row.lastTelemetryTs());
    }

    @Test
    void listIsOrderedByDeviceId() {
        db.tx(conn -> {
            repo.ensure(conn, "GH-0002", 1000, 2);
            repo.ensure(conn, "GH-0001", 1000, 2);
            return null;
        });
        List<DeviceRow> rows = repo.list();
        assertEquals(2, rows.size());
        assertEquals("GH-0001", rows.get(0).deviceId());
        assertTrue(repo.find("GH-9999").isEmpty());
    }
}
