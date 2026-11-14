package greenhouse.db.mqtt;

import java.util.concurrent.atomic.AtomicLong;

/** 入库计数，{@code /health} 与日志用。全部是单调递增的累计值。 */
public final class IngestStats {

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong inserted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong badPayloads = new AtomicLong();
    private final AtomicLong retainedMessages = new AtomicLong();
    private final AtomicLong alarmEvents = new AtomicLong();
    private final AtomicLong statusEvents = new AtomicLong();

    void received() {
        received.incrementAndGet();
    }

    void inserted() {
        inserted.incrementAndGet();
    }

    void duplicate() {
        duplicates.incrementAndGet();
    }

    void badPayload() {
        badPayloads.incrementAndGet();
    }

    void retained() {
        retainedMessages.incrementAndGet();
    }

    void alarmEvent() {
        alarmEvents.incrementAndGet();
    }

    void statusEvent() {
        statusEvents.incrementAndGet();
    }

    public long receivedCount() {
        return received.get();
    }

    public long insertedCount() {
        return inserted.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long badPayloadCount() {
        return badPayloads.get();
    }

    public long retainedCount() {
        return retainedMessages.get();
    }

    public long alarmEventCount() {
        return alarmEvents.get();
    }

    public long statusEventCount() {
        return statusEvents.get();
    }
}
