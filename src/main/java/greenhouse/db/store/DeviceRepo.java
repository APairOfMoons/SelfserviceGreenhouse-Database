package greenhouse.db.store;

import greenhouse.db.model.DeviceRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 设备维表读写。
 * <p>
 * 这一层不猜会话：{@link #normalizeB} 只做"转小写 + 格式校验"，
 * 不合规返回 {@code null}，由调用方当坏报文拒收（PLAN 5.2）。
 */
public final class DeviceRepo {

    private static final String COLUMNS =
            "device_id, proto_version, first_seen_ts, last_seen_ts, online,"
                    + " last_status_ts, last_telemetry_ts, cur_b, last_up, last_seq";

    /** 协议承诺 8 位小写十六进制；列宽留到 16 位，防将来放宽。 */
    private static final int B_MAX_LENGTH = 16;

    private final Database db;

    public DeviceRepo(Database db) {
        this.db = db;
    }

    /**
     * 规范化会话号 {@code b}。
     *
     * @return 小写的合法值；null 表示格式非法（空串、非十六进制、过长）
     */
    public static String normalizeB(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.length() > B_MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return lower;
    }

    /**
     * 保证设备行存在并刷新最后活动时间。单写者，无需 SELECT-then-INSERT 的锁。
     */
    public void ensure(Connection conn, String deviceId, long ts, Integer proto) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE device SET last_seen_ts = ?, proto_version = COALESCE(?, proto_version)"
                        + " WHERE device_id = ?")) {
            ps.setLong(1, ts);
            if (proto == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, proto);
            }
            ps.setString(3, deviceId);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO device (device_id, proto_version, first_seen_ts, last_seen_ts)"
                        + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, deviceId);
            if (proto == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, proto);
            }
            ps.setLong(3, ts);
            ps.setLong(4, ts);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 唯一键冲突只可能来自并发插入；单写者下当作已存在处理。
            if (!"23505".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    /** 记录一条遥测带来的会话信息。调用前必须已 {@link #ensure}。 */
    public void markTelemetry(Connection conn, String deviceId, long ts, String b, Long up, Long seq)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE device SET last_telemetry_ts = ?, cur_b = ?, last_up = ?, last_seq = ?"
                        + " WHERE device_id = ?")) {
            ps.setLong(1, ts);
            ps.setString(2, b);
            setNullableLong(ps, 3, up);
            setNullableLong(ps, 4, seq);
            ps.setString(5, deviceId);
            ps.executeUpdate();
        }
    }

    /**
     * 更新在线状态。
     *
     * @param b 可为 null（LWT 不带会话号），此时保留原有的 cur_b
     */
    public void setOnline(Connection conn, String deviceId, long ts, boolean online, String b)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE device SET online = ?, last_status_ts = ?, last_seen_ts = ?,"
                        + " cur_b = COALESCE(?, cur_b) WHERE device_id = ?")) {
            ps.setBoolean(1, online);
            ps.setLong(2, ts);
            ps.setLong(3, ts);
            ps.setString(4, b);
            ps.setString(5, deviceId);
            ps.executeUpdate();
        }
    }

    public Optional<DeviceRow> find(String deviceId) {
        return db.tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM device WHERE device_id = ?")) {
                ps.setString(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<DeviceRow> list() {
        return db.tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM device ORDER BY device_id");
                 ResultSet rs = ps.executeQuery()) {
                List<DeviceRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return rows;
            }
        });
    }

    private static DeviceRow map(ResultSet rs) throws SQLException {
        return new DeviceRow(
                rs.getString("device_id"),
                rs.getObject("proto_version", Integer.class),
                rs.getLong("first_seen_ts"),
                rs.getLong("last_seen_ts"),
                rs.getObject("online", Boolean.class),
                rs.getObject("last_status_ts", Long.class),
                rs.getObject("last_telemetry_ts", Long.class),
                rs.getString("cur_b"),
                rs.getObject("last_up", Long.class),
                rs.getObject("last_seq", Long.class));
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
