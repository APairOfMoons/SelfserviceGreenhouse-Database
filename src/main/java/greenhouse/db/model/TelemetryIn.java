package greenhouse.db.model;

/**
 * 一条已解析、尚未落库的遥测报文（协议 v2）。
 * <p>
 * 数值一律保持设备原始整数（0.01 单位），换算只发生在 HTTP 出口。
 * {@code b} 保持报文原样，规范化（转小写 + 校验）由 {@code store} 层负责，
 * 解析层只保证它不为空。
 */
public record TelemetryIn(
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

    /** 换成规范化后的 {@code b}，其余字段不变。 */
    public TelemetryIn withB(String normalizedB) {
        return new TelemetryIn(deviceId, normalizedB, seq, up, recvTs, proto,
                tempC100, ahumP100, shumP100, lux, water, fan, light, heat, rawJson);
    }
}
