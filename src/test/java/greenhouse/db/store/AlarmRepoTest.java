package greenhouse.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import greenhouse.db.model.AlarmIn;
import greenhouse.db.model.AlarmStateRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlarmRepoTest {

    private static final long TS = 1_000_000L;

    private Database db;
    private AlarmRepo repo;

    @BeforeEach
    void setUp() {
        db = TestSupport.newDatabase();
        repo = new AlarmRepo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void firstNonRetainedAlarmWritesEvent() {
        assertTrue(apply(alarm(true, TS), false));
        assertEquals(1, TestSupport.count(db, "alarm_event"));

        List<AlarmStateRow> active = repo.listActive("GH-0001");
        assertEquals(1, active.size());
        assertEquals("water_lack", active.get(0).code());
    }

    @Test
    void retainedAlarmOnlyUpdatesState() {
        // 刚订阅时 broker 会重放当前告警，这不是"新的告警发生"。
        assertTrue(apply(alarm(true, TS), true));
        assertEquals(0, TestSupport.count(db, "alarm_event"));
        assertEquals(1, TestSupport.count(db, "alarm_state"));
    }

    @Test
    void repeatedSameStateWritesNothing() {
        apply(alarm(true, TS), false);
        assertFalse(apply(alarm(true, TS + 1), false));

        assertEquals(1, TestSupport.count(db, "alarm_event"));
        AlarmStateRow state = repo.listActive("GH-0001").get(0);
        assertEquals(TS, state.firstTs());
        assertEquals(TS + 1, state.lastTs());
    }

    @Test
    void explicitClearWritesEvent() {
        apply(alarm(true, TS), false);
        // 当前固件不发解除，但协议允许，收到就要忠实记录。
        assertTrue(apply(alarm(false, TS + 10), false));

        assertEquals(2, TestSupport.count(db, "alarm_event"));
        assertTrue(repo.listActive("GH-0001").isEmpty());
    }

    @Test
    void retainedStateFlipDoesNotWriteEvent() {
        apply(alarm(true, TS), false);
        assertTrue(apply(alarm(false, TS + 10), true));

        assertEquals(1, TestSupport.count(db, "alarm_event"));
        assertTrue(repo.listActive("GH-0001").isEmpty());
    }

    @Test
    void eventPageIsFilteredByWindow() {
        apply(alarm(true, 1000), false);
        apply(alarm(false, 2000), false);

        assertEquals(2, repo.page("GH-0001", 0, 5000, null, 100).size());
        assertEquals(1, repo.page("GH-0001", 1500, 5000, null, 100).size());
        assertEquals(0, repo.page("GH-0002", 0, 5000, null, 100).size());
    }

    private boolean apply(AlarmIn in, boolean retained) {
        return db.tx(conn -> repo.apply(conn, in, retained));
    }

    private static AlarmIn alarm(boolean active, long ts) {
        return new AlarmIn("GH-0001", "water_lack", active, 100L, "7f3a91c4", ts, "{}");
    }
}
