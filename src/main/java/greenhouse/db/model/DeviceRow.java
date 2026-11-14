package greenhouse.db.model;

/** device 表的一行：设备维表兼最新状态快照。 */
public record DeviceRow(
        String deviceId,
        Integer proto,
        long firstSeenTs,
        long lastSeenTs,
        Boolean online,
        Long lastStatusTs,
        Long lastTelemetryTs,
        String curB,
        Long lastUp,
        Long lastSeq) {
}
