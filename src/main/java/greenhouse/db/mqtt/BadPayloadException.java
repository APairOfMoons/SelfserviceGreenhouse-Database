package greenhouse.db.mqtt;

/**
 * 报文内容不合法：不是 JSON、缺必填字段、类型不对、超长。
 * <p>
 * 这类错误一律"计数 + 记日志 + 丢弃并 ACK"，绝不能让它冒泡到 Paho——
 * messageArrived 抛异常会断开连接，QoS1 会让 broker 无限重投同一条坏报文。
 */
public class BadPayloadException extends Exception {

    public BadPayloadException(String message) {
        super(message);
    }
}
