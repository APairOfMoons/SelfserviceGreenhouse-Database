package greenhouse.db.model;

/** telemetry 表的一行。{@code rawJson} 原文保留，未知字段从这里还原。 */
public record TelemetryRow(
        long id,
        String deviceId,
        String b,
        long seq,
        Long up,
        long recvTs,
        Integer proto,
        Integer tempC100,
        Integer ahumP100,
        Integer shumP100,
        Integer lux,
        Integer water,
        Integer fan,
        Integer light,
        Integer heat,
        String rawJson) {
}
