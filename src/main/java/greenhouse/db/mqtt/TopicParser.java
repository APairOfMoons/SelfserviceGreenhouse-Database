package greenhouse.db.mqtt;

import java.util.Locale;

/**
 * 主题解析。纯函数，不碰网络也不碰数据库。
 * <p>
 * 只认数据服务器订阅的三个形状：
 * {@code gh/<id>/telemetry}、{@code gh/<id>/alarm/<code>}、{@code gh/<id>/status}。
 * {@code config} 与 {@code config/ack} 属于客户端 ↔ 设备，不归这里，落到下面返回空。
 */
public final class TopicParser {

    public enum Kind {
        TELEMETRY,
        ALARM,
        STATUS
    }

    /**
     * 解析结果。
     *
     * @param code 仅 {@link Kind#ALARM} 有值，其余为 null
     */
    public record Parsed(Kind kind, String deviceId, String code) {
    }

    private static final int MAX_DEVICE_ID = 64;
    private static final int MAX_CODE = 64;

    private TopicParser() {
    }

    /** @return 不认识的主题返回 null */
    public static Parsed parse(String topic) {
        if (topic == null || topic.isEmpty()) {
            return null;
        }
        String[] parts = topic.split("/", -1);
        if (parts.length < 3 || !"gh".equals(parts[0])) {
            return null;
        }

        String deviceId = parts[1];
        if (deviceId.isEmpty() || deviceId.length() > MAX_DEVICE_ID
                || deviceId.indexOf('+') >= 0 || deviceId.indexOf('#') >= 0) {
            return null;
        }

        if (parts.length == 3) {
            switch (parts[2]) {
                case "telemetry":
                    return new Parsed(Kind.TELEMETRY, deviceId, null);
                case "status":
                    return new Parsed(Kind.STATUS, deviceId, null);
                default:
                    return null;
            }
        }

        if (parts.length == 4 && "alarm".equals(parts[2])) {
            String code = parts[3].toLowerCase(Locale.ROOT);
            if (code.isEmpty() || code.length() > MAX_CODE) {
                return null;
            }
            return new Parsed(Kind.ALARM, deviceId, code);
        }

        return null;
    }
}
