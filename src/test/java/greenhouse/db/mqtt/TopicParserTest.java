package greenhouse.db.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TopicParserTest {

    @Test
    void parsesTelemetry() {
        TopicParser.Parsed parsed = TopicParser.parse("gh/GH-0001/telemetry");
        assertEquals(TopicParser.Kind.TELEMETRY, parsed.kind());
        assertEquals("GH-0001", parsed.deviceId());
        assertNull(parsed.code());
    }

    @Test
    void parsesStatus() {
        TopicParser.Parsed parsed = TopicParser.parse("gh/GH-0001/status");
        assertEquals(TopicParser.Kind.STATUS, parsed.kind());
        assertEquals("GH-0001", parsed.deviceId());
    }

    @Test
    void parsesAlarmWithCode() {
        TopicParser.Parsed parsed = TopicParser.parse("gh/GH-0001/alarm/water_lack");
        assertEquals(TopicParser.Kind.ALARM, parsed.kind());
        assertEquals("GH-0001", parsed.deviceId());
        assertEquals("water_lack", parsed.code());
    }

    @Test
    void alarmCodeIsLowerCased() {
        assertEquals("turntable_failed",
                TopicParser.parse("gh/GH-0001/alarm/Turntable_Failed").code());
    }

    @Test
    void ignoresClientTopics() {
        // 配置下发与回执属于客户端 ↔ 设备，数据服务器不订阅也不入库。
        assertNull(TopicParser.parse("gh/GH-0001/config"));
        assertNull(TopicParser.parse("gh/GH-0001/config/ack"));
    }

    @Test
    void ignoresAlarmWithoutCode() {
        assertNull(TopicParser.parse("gh/GH-0001/alarm"));
        assertNull(TopicParser.parse("gh/GH-0001/alarm/"));
    }

    @Test
    void ignoresForeignOrMalformedTopics() {
        assertNull(TopicParser.parse(null));
        assertNull(TopicParser.parse(""));
        assertNull(TopicParser.parse("gh"));
        assertNull(TopicParser.parse("gh/GH-0001"));
        assertNull(TopicParser.parse("other/GH-0001/telemetry"));
        assertNull(TopicParser.parse("gh//telemetry"));
        assertNull(TopicParser.parse("gh/+/telemetry"));
        assertNull(TopicParser.parse("gh/GH-0001/telemetry/extra"));
    }
}
