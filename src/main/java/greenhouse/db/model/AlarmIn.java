package greenhouse.db.model;

/**
 * 一条已解析的告警报文。
 * <p>
 * {@code code} 来自主题（{@code gh/<id>/alarm/<code>}），不来自载荷。
 */
public record AlarmIn(
        String deviceId,
        String code,
        boolean active,
        Long up,
        String b,
        long recvTs,
        String rawJson) {
}
