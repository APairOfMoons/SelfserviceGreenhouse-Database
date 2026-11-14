package greenhouse.db.store;

import greenhouse.db.model.AlarmEventRow;
import greenhouse.db.model.AlarmIn;
import greenhouse.db.model.AlarmStateRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警的状态表与事件流。
 * <p>
 * 关键语义（PLAN 8.3）：retained 报文只更新当前状态，<b>不写事件</b>，
 * 否则每次重连都会凭空多出一条"告警发生"。
 */
public final class AlarmRepo {

    private final Database db;

    public AlarmRepo(Database db) {
        this.db = db;
    }

    /**
     * 应用一条告警报文。
     *
     * @param retained Broker 重放的保留消息
     * @return 当前状态是否发生了变化
     */
    public boolean apply(Connection conn, AlarmIn in, boolean retained) throws SQLException {
        Boolean previousActive = null;
        long previousFirstTs = 0L;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT active, first_ts FROM alarm_state WHERE device_id = ? AND code = ?")) {
            ps.setString(1, in.deviceId());
            ps.setString(2, in.code());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    previousActive = rs.getBoolean("active");
                    previousFirstTs = rs.getLong("first_ts");
                }
            }
        }

        boolean changed = previousActive == null || previousActive != in.active();
        long firstTs = changed ? in.recvTs() : previousFirstTs;

        if (previousActive == null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO alarm_state (device_id, code, active, first_ts, last_ts, b, raw_json)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, in.deviceId());
                ps.setString(2, in.code());
                ps.setBoolean(3, in.active());
                ps.setLong(4, firstTs);
                ps.setLong(5, in.recvTs());
                ps.setString(6, in.b());
                ps.setString(7, in.rawJson());
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE alarm_state SET active = ?, first_ts = ?, last_ts = ?, b = ?, raw_json = ?"
                            + " WHERE device_id = ? AND code = ?")) {
                ps.setBoolean(1, in.active());
                ps.setLong(2, firstTs);
                ps.setLong(3, in.recvTs());
                ps.setString(4, in.b());
                ps.setString(5, in.rawJson());
                ps.setString(6, in.deviceId());
                ps.setString(7, in.code());
                ps.executeUpdate();
            }
        }

        if (changed && !retained) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO alarm_event (device_id, code, active, b, recv_ts) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, in.deviceId());
                ps.setString(2, in.code());
                ps.setBoolean(3, in.active());
                ps.setString(4, in.b());
                ps.setLong(5, in.recvTs());
                ps.executeUpdate();
            }
        }
        return changed;
    }

    /** 当前处于激活状态的告警。 */
    public List<AlarmStateRow> listActive(String deviceId) {
        return db.tx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT device_id, code, active, first_ts, last_ts, b, raw_json FROM alarm_state"
                            + " WHERE device_id = ? AND active = TRUE ORDER BY code")) {
                ps.setString(1, deviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<AlarmStateRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new AlarmStateRow(
                                rs.getString("device_id"),
                                rs.getString("code"),
                                rs.getBoolean("active"),
                                rs.getLong("first_ts"),
                                rs.getLong("last_ts"),
                                rs.getString("b"),
                                rs.getString("raw_json")));
                    }
                    return rows;
                }
            }
        });
    }

    /** 告警事件流，正序分页。 */
    public List<AlarmEventRow> page(String deviceId, long fromTs, long toTs, Long afterId, int limit) {
        return db.tx(conn -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, device_id, code, active, b, recv_ts FROM alarm_event"
                            + " WHERE device_id = ? AND recv_ts >= ? AND recv_ts <= ?");
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
                    List<AlarmEventRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new AlarmEventRow(
                                rs.getLong("id"),
                                rs.getString("device_id"),
                                rs.getString("code"),
                                rs.getBoolean("active"),
                                rs.getString("b"),
                                rs.getLong("recv_ts")));
                    }
                    return rows;
                }
            }
        });
    }
}
