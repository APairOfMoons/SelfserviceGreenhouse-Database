package greenhouse.db.store;

import greenhouse.db.model.StatusEventRow;
import greenhouse.db.model.StatusIn;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线状态的事件流。当前值存在 {@code device.online} 上，
 * 这里只负责"变化时写一条历史"。
 */
public final class StatusRepo {

    private final Database db;

    public StatusRepo(Database db) {
        this.db = db;
    }

    /**
     * 比较并记录状态变化。调用前设备行必须已由 {@link DeviceRepo#ensure} 建好。
     *
     * @return 在线状态是否发生了变化
     */
    public boolean apply(Connection conn, StatusIn in, boolean retained) throws SQLException {
        Boolean previousOnline = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT online FROM device WHERE device_id = ?")) {
            ps.setString(1, in.deviceId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    previousOnline = rs.getObject("online", Boolean.class);
                }
            }
        }

        boolean changed = previousOnline == null || previousOnline != in.online();
        if (changed && !retained) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO status_event (device_id, online, b, up_s, recv_ts)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, in.deviceId());
                ps.setBoolean(2, in.online());
                ps.setString(3, in.b());
                if (in.up() == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, in.up());
                }
                ps.setLong(5, in.recvTs());
                ps.executeUpdate();
            }
        }
        return changed;
    }

    /** 在线状态变化历史，正序分页。 */
    public List<StatusEventRow> page(String deviceId, long fromTs, long toTs, Long afterId, int limit) {
        return db.tx(conn -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, device_id, online, b, up_s, recv_ts FROM status_event"
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
                    List<StatusEventRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new StatusEventRow(
                                rs.getLong("id"),
                                rs.getString("device_id"),
                                rs.getBoolean("online"),
                                rs.getString("b"),
                                rs.getObject("up_s", Long.class),
                                rs.getLong("recv_ts")));
                    }
                    return rows;
                }
            }
        });
    }
}
