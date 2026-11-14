package greenhouse.db.model;

/** alarm_state 表的一行：某设备某告警码的当前状态。 */
public record AlarmStateRow(
        String deviceId,
        String code,
        boolean active,
        long firstTs,
        long lastTs,
        String b,
        String rawJson) {
}
