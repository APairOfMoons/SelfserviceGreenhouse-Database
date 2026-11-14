package greenhouse.db.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import greenhouse.db.model.TelemetryRow;
import java.util.Set;

/**
 * 唯一的"整数 → 物理量"出口。
 * <p>
 * 数据库里存的是设备原始整数（0.01 单位），出去之前在这里除以 100；
 * 对外字段名带单位后缀（{@code tempC}、{@code ahumPct}），避免客户端再猜量纲。
 */
public final class Json {

    /**
     * 序列化必须开 {@code serializeNulls}：{@code nextCursor} 用显式 null 表示"没有下一页"，
     * 而 Gson 默认会把值为 JsonNull 的成员整个省略，客户端就分不清"没有更多"和"字段不存在"。
     */
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** 载荷里已知的键；其余键进 {@code extra}。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "v", "b", "seq", "up", "t", "ah", "sh", "lux", "water", "fan", "light", "heat");

    private Json() {
    }

    /** 遥测的物理量视图。缺测字段直接不出现，不填 null。 */
    public static JsonObject values(TelemetryRow row) {
        JsonObject values = new JsonObject();
        putScaled(values, "tempC", row.tempC100());
        putScaled(values, "ahumPct", row.ahumP100());
        putScaled(values, "shumPct", row.shumP100());
        putInt(values, "lux", row.lux());
        putFlag(values, "water", row.water());
        putFlag(values, "fan", row.fan());
        putFlag(values, "light", row.light());
        putFlag(values, "heat", row.heat());
        return values;
    }

    /** 原始报文里未被识别成列的键，供客户端前向兼容使用。 */
    public static JsonObject extra(TelemetryRow row) {
        JsonObject extra = new JsonObject();
        String rawJson = row.rawJson();
        if (rawJson == null || rawJson.isEmpty()) {
            return extra;
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            if (!parsed.isJsonObject()) {
                return extra;
            }
            for (var entry : parsed.getAsJsonObject().entrySet()) {
                if (!KNOWN_KEYS.contains(entry.getKey())) {
                    extra.add(entry.getKey(), entry.getValue());
                }
            }
        } catch (JsonParseException | IllegalStateException e) {
            // raw_json 被截断或历史数据损坏：不影响正常字段，直接放弃 extra。
        }
        return extra;
    }

    /** 历史列表里的一条。{@code b}/{@code seq} 必带，客户端靠它们与实时消息去重。 */
    public static JsonObject telemetryItem(TelemetryRow row) {
        JsonObject item = new JsonObject();
        item.addProperty("recvTs", row.recvTs());
        item.addProperty("b", row.b());
        item.addProperty("seq", row.seq());
        if (row.up() != null) {
            item.addProperty("up", row.up());
        }
        if (row.proto() != null) {
            item.addProperty("proto", row.proto());
        }
        item.add("values", values(row));
        JsonObject extra = extra(row);
        if (!extra.isEmpty()) {
            item.add("extra", extra);
        }
        return item;
    }

    private static void putScaled(JsonObject target, String key, Integer hundredths) {
        if (hundredths != null) {
            target.addProperty(key, hundredths / 100.0);
        }
    }

    private static void putInt(JsonObject target, String key, Integer value) {
        if (value != null) {
            target.addProperty(key, value);
        }
    }

    private static void putFlag(JsonObject target, String key, Integer value) {
        if (value != null) {
            target.addProperty(key, value != 0);
        }
    }
}
