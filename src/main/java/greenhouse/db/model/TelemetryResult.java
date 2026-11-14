package greenhouse.db.model;

/** 遥测写入结果：要么新入库，要么被唯一键判为重复（QoS1 重投）。 */
public enum TelemetryResult {
    INSERTED,
    DUPLICATE
}
