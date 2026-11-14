package greenhouse.db.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** {@code from}/{@code to} 的解析：epoch 毫秒或 ISO-8601，非法值报 400。 */
public final class TimeParam {

    private TimeParam() {
    }

    public static long parse(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // 不是 epoch，继续按 ISO-8601 试
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 落到下面报错
        }
        throw new ApiException(400, "bad_request",
                "时间参数无法解析（支持 epoch 毫秒或 ISO-8601）: " + value);
    }
}
