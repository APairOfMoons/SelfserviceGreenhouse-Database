package greenhouse.db.model;

/** status_event 表的一行：一次在线状态变化（retained 重放不产生行）。 */
public record StatusEventRow(
        long id,
        String deviceId,
        boolean online,
        String b,
        Long up,
        long recvTs) {
}
