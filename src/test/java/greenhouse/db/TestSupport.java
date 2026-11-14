package greenhouse.db;

import greenhouse.db.model.TelemetryIn;
import greenhouse.db.store.Database;
import greenhouse.db.store.Schema;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 测试共用的小工具：每个用例一个独立的内存库。 */
public final class TestSupport {

    private TestSupport() {
    }

    public static Database newDatabase() {
        Database db = Database.openMemory("gh_" + UUID.randomUUID().toString().replace("-", ""));
        Schema.migrate(db);
        return db;
    }

    public static byte[] ascii(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** 一条典型的 v2 遥测。 */
    public static TelemetryIn telemetry(String deviceId, String b, long seq, long recvTs) {
        return new TelemetryIn(deviceId, b, seq, 86400L, recvTs, 2,
                2350, 5680, 3120, 812, 1, 0, 1, 0, "{}");
    }

    /** 表里的行数，供断言用。表名由测试代码写死，不接受外部输入。 */
    public static int count(Database db, String table) {
        return db.tx(conn -> {
            try (var statement = conn.createStatement();
                 var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }
}
