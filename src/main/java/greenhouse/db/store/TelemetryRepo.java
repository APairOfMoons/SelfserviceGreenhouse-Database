package greenhouse.db.store;

import greenhouse.db.model.TelemetryIn;
import greenhouse.db.model.TelemetryResult;
import greenhouse.db.model.TelemetryRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 遥测的写入与查询。写入幂等由唯一键 {@code (device_id, b, seq)} 保证。 */
public final class TelemetryRepo {

    private static final String COLUMNS =
            "id, device_id, b, seq, up_s, recv_ts, proto_v,"
                    + " temp_c100, ahum_p100, shum_p100, lux, water, fan, light, heat, raw_json";

    private static final String INSERT =
            "INSERT INTO telemetry (device_id, b, seq, up_s, recv_ts, proto_v,"
                    + " temp_c100, ahum_p100, shum_p100, lux, water, fan, light, heat, raw_json)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** H2/标准 SQL 的唯一约束冲突。 */
    private static final String UNIQUE_VIOLATION = "23505";

    private final Database db;

    public TelemetryRepo(Database db) {
        this.db = db;
    }

    /**
     * 写入一条遥测。重投的报文命中原有唯一键时返回 {@link TelemetryResult#DUPLICATE}，
     * 不抛异常——QoS1 的"至少一次"是正常情况，不是错误。
     */
    public TelemetryResult insert(Connection conn, TelemetryIn in) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            ps.setString(1, in.deviceId());
            ps.setString(2, in.b());
            ps.setLong(3, in.seq());
            setNullableLong(ps, 4, in.up());
            ps.setLong(5, in.recvTs());
            setNullableInt(ps, 6, in.proto());
            setNullableInt(ps, 7, in.tempC100());
            setNullableInt(ps, 8, in.ahumP100());
            setNullableInt(ps, 9, in.shumP100());
            setNullableInt(ps, 10, in.lux());
            setNullableInt(ps, 11, in.water());
            setNullableInt(ps, 12, in.fan());
            setNullableInt(ps, 13, in.light());
            setNullableInt(ps, 14, in.heat());
            ps.setString(15, in.rawJson());
            ps.executeUpdate();
            return TelemetryResult.INSERTED;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return TelemetryResult.DUPLICATE;
            }
            throw e;
        }
    }

    /** 某设备最近一条遥测。 */
    public Optional<TelemetryRow> latest(String deviceId) {
        return db.tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + COLUMNS + " FROM telemetry WHERE device_id = ?"
                            + " ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * 按时间窗口取历史，正序（旧 → 新）。
     *
     * @param afterId 游标：只返回 id 更大的记录；null 表示从头开始
     * @param limit   条数上限，由调用方校验
     */
    public List<TelemetryRow> page(String deviceId, long fromTs, long toTs, Long afterId, int limit) {
        return db.tx(conn -> {
            StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                    .append(" FROM telemetry WHERE device_id = ? AND recv_ts >= ? AND recv_ts <= ?");
            if (afterId != null) {
                sql.append(" AND id > ?");
            }
            sql.append(" ORDER BY id ASC LIMIT ?");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setString(i++, deviceId);
                ps.setLong(i++, fromTs);
                ps.setLong(i++, toTs);
                if (afterId != null) {
                    ps.setLong(i++, afterId);
                }
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TelemetryRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                    return rows;
                }
            }
        });
    }

    private static TelemetryRow map(ResultSet rs) throws SQLException {
        return new TelemetryRow(
                rs.getLong("id"),
                rs.getString("device_id"),
                rs.getString("b"),
                rs.getLong("seq"),
                rs.getObject("up_s", Long.class),
                rs.getLong("recv_ts"),
                rs.getObject("proto_v", Integer.class),
                rs.getObject("temp_c100", Integer.class),
                rs.getObject("ahum_p100", Integer.class),
                rs.getObject("shum_p100", Integer.class),
                rs.getObject("lux", Integer.class),
                rs.getObject("water", Integer.class),
                rs.getObject("fan", Integer.class),
                rs.getObject("light", Integer.class),
                rs.getObject("heat", Integer.class),
                rs.getString("raw_json"));
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
