package greenhouse.db.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import greenhouse.db.TestSupport;
import greenhouse.db.model.AlarmIn;
import greenhouse.db.model.StatusIn;
import greenhouse.db.model.TelemetryIn;
import org.junit.jupiter.api.Test;

class PayloadsTest {

    private static final long TS = 1_763_100_000_123L;

    @Test
    void parsesFullTelemetry() throws Exception {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":1024,\"up\":86400,"
                + "\"t\":2350,\"ah\":5680,\"sh\":3120,\"lux\":812,"
                + "\"water\":1,\"fan\":0,\"light\":1,\"heat\":0}";

        TelemetryIn in = Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS);

        assertEquals("GH-0001", in.deviceId());
        assertEquals("7f3a91c4", in.b());
        assertEquals(1024, in.seq());
        assertEquals(86400L, in.up());
        assertEquals(TS, in.recvTs());
        assertEquals(2, in.proto());
        assertEquals(2350, in.tempC100());
        assertEquals(5680, in.ahumP100());
        assertEquals(3120, in.shumP100());
        assertEquals(812, in.lux());
        assertEquals(1, in.water());
        assertEquals(0, in.fan());
    }

    @Test
    void keepsUnknownFieldsInRawJson() throws Exception {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":1,\"co2\":420}";
        TelemetryIn in = Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS);
        assertTrue(in.rawJson().contains("co2"), "原始报文必须原样保留");
    }

    @Test
    void missingBIsRejected() {
        String json = "{\"v\":2,\"seq\":1}";
        BadPayloadException e = assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS));
        assertTrue(e.getMessage().contains("b"));
    }

    @Test
    void emptyBIsRejected() {
        String json = "{\"v\":2,\"b\":\"\",\"seq\":1}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS));
    }

    @Test
    void missingSeqIsRejected() {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\"}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS));
    }

    @Test
    void badJsonIsRejected() {
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii("{not json"), TS));
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", new byte[0], TS));
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii("[1,2]"), TS));
    }

    @Test
    void wrongTypeIsRejected() {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":\"1024\"}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS));
    }

    @Test
    void oversizedPayloadIsRejected() {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"seq\":1,\"pad\":\"" + "x".repeat(5000) + "\"}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.telemetry("GH-0001", TestSupport.ascii(json), TS));
    }

    @Test
    void parsesAlarm() throws Exception {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"up\":86400,\"code\":\"water_lack\",\"active\":true}";
        AlarmIn in = Payloads.alarm("GH-0001", "water_lack", TestSupport.ascii(json), TS);

        assertEquals("water_lack", in.code());
        assertTrue(in.active());
        assertEquals("7f3a91c4", in.b());
        assertEquals(86400L, in.up());
    }

    @Test
    void alarmWithoutBIsRejected() {
        String json = "{\"v\":2,\"active\":true}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.alarm("GH-0001", "water_lack", TestSupport.ascii(json), TS));
    }

    @Test
    void alarmWithoutActiveIsRejected() {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\"}";
        assertThrows(BadPayloadException.class,
                () -> Payloads.alarm("GH-0001", "water_lack", TestSupport.ascii(json), TS));
    }

    @Test
    void parsesStatusWithSession() throws Exception {
        String json = "{\"v\":2,\"b\":\"7f3a91c4\",\"online\":true,\"up\":3}";
        StatusIn in = Payloads.status("GH-0001", TestSupport.ascii(json), TS);

        assertTrue(in.online());
        assertEquals("7f3a91c4", in.b());
        assertEquals(3L, in.up());
    }

    @Test
    void parsesLwtWithoutSession() throws Exception {
        // Broker 代发的 LWT 没有 b，不能因为缺 b 就丢掉在线状态。
        String json = "{\"v\":2,\"online\":false}";
        StatusIn in = Payloads.status("GH-0001", TestSupport.ascii(json), TS);

        assertEquals(false, in.online());
        assertNull(in.b());
    }

    @Test
    void statusAcceptsNumericFlags() throws Exception {
        StatusIn in = Payloads.status("GH-0001", TestSupport.ascii("{\"online\":1}"), TS);
        assertTrue(in.online());
    }

    @Test
    void statusWithoutOnlineIsRejected() {
        assertThrows(BadPayloadException.class,
                () -> Payloads.status("GH-0001", TestSupport.ascii("{\"v\":2}"), TS));
    }
}
