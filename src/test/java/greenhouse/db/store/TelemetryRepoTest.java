package greenhouse.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import greenhouse.db.model.TelemetryIn;
import greenhouse.db.model.TelemetryResult;
import greenhouse.db.model.TelemetryRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelemetryRepoTest {

    private Database db;
    private TelemetryRepo repo;

    @BeforeEach
    void setUp() {
        db = TestSupport.newDatabase();
        repo = new TelemetryRepo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void insertThenDuplicate() {
        TelemetryIn in = TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 1000);
        assertEquals(TelemetryResult.INSERTED, db.tx(conn -> repo.insert(conn, in)));
        assertEquals(TelemetryResult.DUPLICATE, db.tx(conn -> repo.insert(conn, in)));
        assertEquals(1, TestSupport.count(db, "telemetry"));
    }

    @Test
    void sameSeqInNewSessionIsNotDuplicate() {
        TelemetryIn first = TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 1000);
        TelemetryIn second = TestSupport.telemetry("GH-0001", "1c04be77", 1, 2000);

        assertEquals(TelemetryResult.INSERTED, db.tx(conn -> repo.insert(conn, first)));
        assertEquals(TelemetryResult.INSERTED, db.tx(conn -> repo.insert(conn, second)));
        assertEquals(2, TestSupport.count(db, "telemetry"));
    }

    @Test
    void sameSeqOnDifferentDevicesIsNotDuplicate() {
        assertEquals(TelemetryResult.INSERTED,
                db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 1000))));
        assertEquals(TelemetryResult.INSERTED,
                db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0002", "7f3a91c4", 1, 1000))));
    }

    @Test
    void latestReturnsNewest() {
        db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 0, 1000)));
        db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 2000)));

        TelemetryRow latest = repo.latest("GH-0001").orElseThrow();
        assertEquals(1, latest.seq());
        assertEquals(2000, latest.recvTs());
        assertTrue(repo.latest("GH-9999").isEmpty());
    }

    @Test
    void pageRespectsWindow() {
        seedThree();

        assertEquals(3, repo.page("GH-0001", 0, 5000, null, 100).size());
        assertEquals(2, repo.page("GH-0001", 1500, 3000, null, 100).size());
        assertEquals(0, repo.page("GH-0001", 4000, 5000, null, 100).size());
    }

    @Test
    void pageUsesCursorToContinue() {
        seedThree();

        List<TelemetryRow> firstPage = repo.page("GH-0001", 0, 5000, null, 2);
        assertEquals(2, firstPage.size());
        assertEquals(1000, firstPage.get(0).recvTs());
        assertEquals(2000, firstPage.get(1).recvTs());

        long cursor = firstPage.get(1).id();
        List<TelemetryRow> secondPage = repo.page("GH-0001", 0, 5000, cursor, 2);
        assertEquals(1, secondPage.size());
        assertEquals(3000, secondPage.get(0).recvTs());
    }

    @Test
    void rawJsonIsStored() {
        TelemetryIn in = new TelemetryIn("GH-0001", "7f3a91c4", 0, null, 1000, 2,
                2350, null, null, null, null, null, null, null, "{\"v\":2,\"b\":\"7f3a91c4\"}");
        db.tx(conn -> repo.insert(conn, in));

        TelemetryRow row = repo.latest("GH-0001").orElseThrow();
        assertEquals("{\"v\":2,\"b\":\"7f3a91c4\"}", row.rawJson());
        assertEquals(2350, row.tempC100());
    }

    private void seedThree() {
        db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 0, 1000)));
        db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 2000)));
        db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 2, 3000)));
    }
}
