package greenhouse.db.mqtt;

import greenhouse.db.model.AlarmIn;
import greenhouse.db.model.StatusIn;
import greenhouse.db.model.TelemetryIn;
import greenhouse.db.model.TelemetryResult;
import greenhouse.db.store.AlarmRepo;
import greenhouse.db.store.Database;
import greenhouse.db.store.DeviceRepo;
import greenhouse.db.store.StatusRepo;
import greenhouse.db.store.TelemetryRepo;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 入库编排：解析 → 校验 {@code b} → 写库 → 更新设备快照。
 * <p>
 * 与 Paho 解耦，测试可以直接调 {@link #onMessage}。
 * <p>
 * <b>异常约定</b>（很重要）：
 * <ul>
 *   <li>报文内容问题（{@link BadPayloadException}）→ 计数 + 限流日志 + 正常返回，
 *       让 Paho 发 PUBACK。否则 QoS1 会让 broker 无限重投同一条坏报文。</li>
 *   <li>存储问题（{@code StoreException}）→ 故意冒泡给 Paho，断开后重连、
 *       broker 重投，等价于"入库成功才确认"。</li>
 * </ul>
 */
public final class IngestService {

    private static final Logger LOG = Logger.getLogger(IngestService.class.getName());

    /** 同一主题的坏报文最多每分钟记一行，防止重投刷屏。 */
    private static final long BAD_LOG_INTERVAL_MS = 60_000L;

    private final Database db;
    private final DeviceRepo devices;
    private final TelemetryRepo telemetry;
    private final AlarmRepo alarms;
    private final StatusRepo statuses;
    private final IngestStats stats;
    private final ConcurrentHashMap<String, Long> lastBadLogAt = new ConcurrentHashMap<>();

    public IngestService(Database db, DeviceRepo devices, TelemetryRepo telemetry,
                         AlarmRepo alarms, StatusRepo statuses, IngestStats stats) {
        this.db = db;
        this.devices = devices;
        this.telemetry = telemetry;
        this.alarms = alarms;
        this.statuses = statuses;
        this.stats = stats;
    }

    public IngestStats stats() {
        return stats;
    }

    /** 处理一条 MQTT 消息。内容错误不抛，存储错误抛。 */
    public void onMessage(String topic, byte[] payload, boolean retained) {
        TopicParser.Parsed parsed = TopicParser.parse(topic);
        if (parsed == null) {
            stats.badPayload();
            warnBad(topic, "忽略无法识别的主题");
            return;
        }

        stats.received();
        if (retained) {
            stats.retained();
        }
        long recvTs = System.currentTimeMillis();

        try {
            switch (parsed.kind()) {
                case TELEMETRY -> onTelemetry(parsed.deviceId(), payload, recvTs);
                case ALARM -> onAlarm(parsed.deviceId(), parsed.code(), payload, recvTs, retained);
                case STATUS -> onStatus(parsed.deviceId(), payload, recvTs, retained);
            }
        } catch (BadPayloadException e) {
            stats.badPayload();
            warnBad(topic, "丢弃坏报文: " + e.getMessage());
        }
    }

    private void onTelemetry(String deviceId, byte[] payload, long recvTs)
            throws BadPayloadException {
        TelemetryIn raw = Payloads.telemetry(deviceId, payload, recvTs);
        String b = DeviceRepo.normalizeB(raw.b());
        if (b == null) {
            throw new BadPayloadException("b 格式非法: " + abbreviate(raw.b()));
        }
        TelemetryIn in = raw.withB(b);

        TelemetryResult result = db.tx(conn -> {
            devices.ensure(conn, deviceId, recvTs, in.proto());
            TelemetryResult r = telemetry.insert(conn, in);
            if (r == TelemetryResult.INSERTED) {
                devices.markTelemetry(conn, deviceId, recvTs, b, in.up(), in.seq());
            }
            return r;
        });

        if (result == TelemetryResult.INSERTED) {
            stats.inserted();
            LOG.log(Level.FINE, "遥测入库 {0} b={1} seq={2}",
                    new Object[]{deviceId, b, Long.toString(in.seq())});
        } else {
            stats.duplicate();
            LOG.log(Level.FINE, "遥测重复丢弃 {0} b={1} seq={2}",
                    new Object[]{deviceId, b, Long.toString(in.seq())});
        }
    }

    private void onAlarm(String deviceId, String code, byte[] payload, long recvTs, boolean retained)
            throws BadPayloadException {
        AlarmIn raw = Payloads.alarm(deviceId, code, payload, recvTs);
        String b = DeviceRepo.normalizeB(raw.b());
        if (b == null) {
            throw new BadPayloadException("告警的 b 格式非法: " + abbreviate(raw.b()));
        }
        AlarmIn in = new AlarmIn(deviceId, code, raw.active(), raw.up(), b, recvTs, raw.rawJson());

        boolean changed = db.tx(conn -> {
            devices.ensure(conn, deviceId, recvTs, null);
            return alarms.apply(conn, in, retained);
        });

        if (changed && !retained) {
            stats.alarmEvent();
            LOG.log(Level.INFO, "告警变化 {0} {1} active={2} b={3}",
                    new Object[]{deviceId, code, in.active(), b});
        } else {
            LOG.log(Level.FINE, "告警状态未变 {0} {1} active={2} retained={3}",
                    new Object[]{deviceId, code, in.active(), retained});
        }
    }

    private void onStatus(String deviceId, byte[] payload, long recvTs, boolean retained)
            throws BadPayloadException {
        StatusIn raw = Payloads.status(deviceId, payload, recvTs);
        String b = raw.b() == null ? null : DeviceRepo.normalizeB(raw.b());
        if (raw.b() != null && b == null) {
            // 状态不参与去重，b 不合法只影响追溯，不值得为它丢掉在线状态。
            LOG.log(Level.FINE, "状态里的 b 不合法，按无会话处理: {0}", abbreviate(raw.b()));
        }

        boolean changed = db.tx(conn -> {
            devices.ensure(conn, deviceId, recvTs, null);
            boolean c = statuses.apply(conn, raw, retained);
            devices.setOnline(conn, deviceId, recvTs, raw.online(), b);
            return c;
        });

        if (changed && !retained) {
            stats.statusEvent();
            LOG.log(Level.INFO, "在线状态变化 {0} online={1} b={2}",
                    new Object[]{deviceId, raw.online(), b});
        } else {
            LOG.log(Level.FINE, "在线状态未变 {0} online={1} retained={2}",
                    new Object[]{deviceId, raw.online(), retained});
        }
    }

    private void warnBad(String topic, String message) {
        long now = System.currentTimeMillis();
        Long previous = lastBadLogAt.get(topic);
        if (previous != null && now - previous < BAD_LOG_INTERVAL_MS) {
            return;
        }
        lastBadLogAt.put(topic, now);
        LOG.log(Level.WARNING, "{0} ({1})", new Object[]{message, topic});
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 32 ? value : value.substring(0, 32) + "...";
    }
}
