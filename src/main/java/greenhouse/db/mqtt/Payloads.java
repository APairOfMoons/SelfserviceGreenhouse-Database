package greenhouse.db.mqtt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import greenhouse.db.model.AlarmIn;
import greenhouse.db.model.StatusIn;
import greenhouse.db.model.TelemetryIn;
import java.nio.charset.StandardCharsets;

/**
 * MQTT 载荷 → 模型对象。用 Gson 的树模型（不反射，字段是写死的白名单）。
 * <p>
 * 校验只做"能不能安全落库"，不做业务合理性判断：温度 300 ℃ 也照存，
 * 数据库是记录者，不是裁判。
 */
public final class Payloads {

    /** 协议上限 450 字节，这里给 4 KB 余量；再大就是异常流量。 */
    private static final int MAX_PAYLOAD_BYTES = 4096;

    /** 与 telemetry.raw_json 列宽一致。 */
    private static final int MAX_TELEMETRY_RAW = 1024;

    /** 与 alarm_state.raw_json 列宽一致。 */
    private static final int MAX_ALARM_RAW = 512;

    private Payloads() {
    }

    public static TelemetryIn telemetry(String deviceId, byte[] payload, long recvTs)
            throws BadPayloadException {
        String text = decode(payload);
        JsonObject obj = parseObject(text);

        // PLAN 5.2：b 必填，缺失即拒收，绝不补空串。
        String b = requireString(obj, "b");
        long seq = requireLong(obj, "seq");

        return new TelemetryIn(
                deviceId,
                b,
                seq,
                optionalLong(obj, "up"),
                recvTs,
                optionalInt(obj, "v"),
                optionalInt(obj, "t"),
                optionalInt(obj, "ah"),
                optionalInt(obj, "sh"),
                optionalInt(obj, "lux"),
                optionalInt(obj, "water"),
                optionalInt(obj, "fan"),
                optionalInt(obj, "light"),
                optionalInt(obj, "heat"),
                fit(text, MAX_TELEMETRY_RAW));
    }

    public static AlarmIn alarm(String deviceId, String code, byte[] payload, long recvTs)
            throws BadPayloadException {
        String text = decode(payload);
        JsonObject obj = parseObject(text);
        boolean active = requireBoolean(obj, "active");
        return new AlarmIn(
                deviceId,
                code,
                active,
                optionalLong(obj, "up"),
                requireString(obj, "b"),
                recvTs,
                fit(text, MAX_ALARM_RAW));
    }

    public static StatusIn status(String deviceId, byte[] payload, long recvTs)
            throws BadPayloadException {
        String text = decode(payload);
        JsonObject obj = parseObject(text);
        boolean online = requireBoolean(obj, "online");
        // LWT 由 Broker 代发，不带 b；status 的 b 允许缺失。
        return new StatusIn(
                deviceId,
                online,
                optionalString(obj, "b"),
                optionalLong(obj, "up"),
                recvTs);
    }

    private static String decode(byte[] payload) throws BadPayloadException {
        if (payload == null || payload.length == 0) {
            throw new BadPayloadException("空载荷");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new BadPayloadException("载荷过大: " + payload.length + " 字节");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String text) throws BadPayloadException {
        JsonElement element;
        try {
            element = JsonParser.parseString(text);
        } catch (JsonParseException | IllegalStateException e) {
            throw new BadPayloadException("不是合法 JSON");
        }
        if (element == null || !element.isJsonObject()) {
            throw new BadPayloadException("载荷不是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            throw new BadPayloadException("缺少字段 " + key);
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BadPayloadException("字段 " + key + " 不是字符串");
        }
        String value = e.getAsString();
        if (value.isEmpty()) {
            throw new BadPayloadException("字段 " + key + " 为空");
        }
        return value;
    }

    private static String optionalString(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BadPayloadException("字段 " + key + " 不是字符串");
        }
        String value = e.getAsString();
        return value.isEmpty() ? null : value;
    }

    private static long requireLong(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            throw new BadPayloadException("缺少字段 " + key);
        }
        Long value = asLong(e, key);
        if (value == null) {
            throw new BadPayloadException("字段 " + key + " 不是整数");
        }
        return value;
    }

    private static Long optionalLong(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        Long value = asLong(e, key);
        if (value == null) {
            throw new BadPayloadException("字段 " + key + " 不是整数");
        }
        return value;
    }

    private static Integer optionalInt(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new BadPayloadException("字段 " + key + " 不是数字");
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            throw new BadPayloadException("字段 " + key + " 超出整数范围");
        }
    }

    private static Long asLong(JsonElement e, String key) throws BadPayloadException {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            throw new BadPayloadException("字段 " + key + " 超出范围");
        }
    }

    private static boolean requireBoolean(JsonObject obj, String key) throws BadPayloadException {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            throw new BadPayloadException("缺少字段 " + key);
        }
        if (!e.isJsonPrimitive()) {
            throw new BadPayloadException("字段 " + key + " 不是布尔值");
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        // 兼容 0/1，固件早期版本用过这种写法。
        if (p.isNumber()) {
            int v = p.getAsInt();
            if (v == 0) {
                return false;
            }
            if (v == 1) {
                return true;
            }
        }
        throw new BadPayloadException("字段 " + key + " 不是布尔值");
    }

    private static String fit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
