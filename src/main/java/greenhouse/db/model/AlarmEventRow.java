package greenhouse.db.model;

/** alarm_event 表的一行：一次状态变化（retained 重放不产生行）。 */
public record AlarmEventRow(
        long id,
        String deviceId,
        String code,
        boolean active,
        String b,
        long recvTs) {
}
