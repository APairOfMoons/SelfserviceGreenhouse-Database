package greenhouse.db.model;

/**
 * 一条已解析的在线状态报文。
 * <p>
 * {@code b} 可为 null：LWT 由 Broker 代发，载荷里没有会话号。
 */
public record StatusIn(
        String deviceId,
        boolean online,
        String b,
        Long up,
        long recvTs) {
}
