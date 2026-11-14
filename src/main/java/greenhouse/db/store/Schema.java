package greenhouse.db.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 建表与版本迁移。
 * <p>
 * 版本号存在 {@code schema_version} 单行表里。以后加字段就是追加一段迁移，
 * 不需要手工判断列是否存在——这是换 MySQL 时唯一需要重写的 SQL 集。
 */
public final class Schema {

    private static final Logger LOG = Logger.getLogger(Schema.class.getName());

    /** 当前程序期望的库版本。 */
    public static final int CURRENT_VERSION = 1;

    private Schema() {
    }

    public static void migrate(Database db) {
        db.tx(conn -> {
            int version = readVersion(conn);
            if (version > CURRENT_VERSION) {
                throw new StoreException("数据库版本 " + version + " 高于程序支持的 " + CURRENT_VERSION
                        + "，请升级程序或换回旧库");
            }
            if (version < 1) {
                applyV1(conn);
                version = 1;
                LOG.log(Level.INFO, "已建库，schema 版本 {0}", version);
            }
            if (version != CURRENT_VERSION) {
                throw new StoreException("schema 版本 " + version + " 未能迁移到 " + CURRENT_VERSION);
            }
            return null;
        });
    }

    private static int readVersion(Connection conn) throws SQLException {
        if (!tableExists(conn, "SCHEMA_VERSION")) {
            return 0;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** v1：四张数据表 + 设备维表 + 版本表。 */
    private static void applyV1(Connection conn) throws SQLException {
        List<String> ddl = List.of(
                "CREATE TABLE schema_version (version INT NOT NULL)",

                // 设备维表，兼最新状态快照
                "CREATE TABLE device ("
                        + " device_id         VARCHAR(64) PRIMARY KEY,"
                        + " proto_version     INT,"
                        + " first_seen_ts     BIGINT NOT NULL,"
                        + " last_seen_ts      BIGINT NOT NULL,"
                        + " online            BOOLEAN,"
                        + " last_status_ts    BIGINT,"
                        + " last_telemetry_ts BIGINT,"
                        + " cur_b             VARCHAR(16),"
                        + " last_up           BIGINT,"
                        + " last_seq          BIGINT"
                        + ")",

                // 遥测，只增不改
                "CREATE TABLE telemetry ("
                        + " id          BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " device_id   VARCHAR(64) NOT NULL,"
                        + " b           VARCHAR(16) NOT NULL,"
                        + " seq         BIGINT      NOT NULL,"
                        + " up_s        BIGINT,"
                        + " recv_ts     BIGINT      NOT NULL,"
                        + " proto_v     INT,"
                        + " temp_c100   INT,"
                        + " ahum_p100   INT,"
                        + " shum_p100   INT,"
                        + " lux         INT,"
                        + " water       INT,"
                        + " fan         INT,"
                        + " light       INT,"
                        + " heat        INT,"
                        + " raw_json    VARCHAR(1024),"
                        + " CONSTRAINT uq_telemetry UNIQUE (device_id, b, seq)"
                        + ")",
                "CREATE INDEX ix_telemetry_dev_id ON telemetry (device_id, id)",
                "CREATE INDEX ix_telemetry_dev_ts ON telemetry (device_id, recv_ts)",

                // 告警当前状态
                "CREATE TABLE alarm_state ("
                        + " device_id VARCHAR(64),"
                        + " code      VARCHAR(64),"
                        + " active    BOOLEAN NOT NULL,"
                        + " first_ts  BIGINT  NOT NULL,"
                        + " last_ts   BIGINT  NOT NULL,"
                        + " b         VARCHAR(16),"
                        + " raw_json  VARCHAR(512),"
                        + " PRIMARY KEY (device_id, code)"
                        + ")",

                // 告警事件流（只记状态变化）
                "CREATE TABLE alarm_event ("
                        + " id        BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " device_id VARCHAR(64) NOT NULL,"
                        + " code      VARCHAR(64) NOT NULL,"
                        + " active    BOOLEAN     NOT NULL,"
                        + " b         VARCHAR(16),"
                        + " recv_ts   BIGINT      NOT NULL"
                        + ")",
                "CREATE INDEX ix_alarm_event_dev_ts ON alarm_event (device_id, recv_ts)",

                // 在线状态变化
                "CREATE TABLE status_event ("
                        + " id        BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + " device_id VARCHAR(64) NOT NULL,"
                        + " online    BOOLEAN     NOT NULL,"
                        + " b         VARCHAR(16),"
                        + " up_s      BIGINT,"
                        + " recv_ts   BIGINT      NOT NULL"
                        + ")",
                "CREATE INDEX ix_status_event_dev_ts ON status_event (device_id, recv_ts)",

                "INSERT INTO schema_version (version) VALUES (1)");

        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }
}
