package greenhouse.db.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文件模式的关键性质：重启后数据还在、迁移可重复执行、在线备份能产出文件。 */
class DatabaseTest {

    @Test
    void dataSurvivesReopen(@TempDir Path dir) {
        Path dataDir = dir.resolve("data");

        Database first = Database.open(dataDir, "ghdb");
        try {
            Schema.migrate(first);
            TelemetryRepo repo = new TelemetryRepo(first);
            first.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 1000)));
        } finally {
            first.close();
        }

        Database second = Database.open(dataDir, "ghdb");
        try {
            // 迁移必须可重复执行
            Schema.migrate(second);
            assertEquals(1, TestSupport.count(second, "telemetry"));
            assertEquals("7f3a91c4",
                    new TelemetryRepo(second).latest("GH-0001").orElseThrow().b());
        } finally {
            second.close();
        }
    }

    @Test
    void backupProducesFile(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Database db = Database.open(dataDir, "ghdb");
        try {
            Schema.migrate(db);
            TelemetryRepo repo = new TelemetryRepo(db);
            db.tx(conn -> repo.insert(conn, TestSupport.telemetry("GH-0001", "7f3a91c4", 1, 1000)));

            Path backup = dir.resolve("backups").resolve("ghdb.zip");
            db.backup(backup);

            assertTrue(Files.isRegularFile(backup), "备份文件未生成");
            assertTrue(Files.size(backup) > 0, "备份文件为空");
        } finally {
            db.close();
        }
    }

    @Test
    void newerSchemaIsRejected(@TempDir Path dir) {
        Path dataDir = dir.resolve("data");
        Database db = Database.open(dataDir, "ghdb");
        try {
            Schema.migrate(db);
            db.tx(conn -> {
                try (var st = conn.createStatement()) {
                    st.execute("UPDATE schema_version SET version = 99");
                }
                return null;
            });
            boolean rejected = false;
            try {
                Schema.migrate(db);
            } catch (StoreException expected) {
                rejected = true;
            }
            assertTrue(rejected, "程序不应打开比自己更新的库");
        } finally {
            db.close();
        }
    }
}
