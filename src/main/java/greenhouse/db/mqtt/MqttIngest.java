package greenhouse.db.mqtt;

import greenhouse.db.config.Config;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Paho 生命周期：连接、重连、订阅、把消息交给 {@link IngestService}。
 * <p>
 * 订阅在 {@code connectComplete} 里做，所以初次连接与每次重连都会重新订阅——
 * broker 侧会话被清掉后，这也是唯一能恢复订阅的地方。
 */
public final class MqttIngest implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MqttIngest.class.getName());

    private final Config config;
    private final IngestService ingest;
    private final MqttClient client;

    public MqttIngest(Config config, IngestService ingest) throws MqttException {
        this.config = config;
        this.ingest = ingest;
        this.client = new MqttClient(config.mqttUrl(), config.mqttClientId(), new MemoryPersistence());
        this.client.setCallback(new Callback());
    }

    /** 阻塞直到连上；失败抛出，由 App 决定退出。 */
    public void start() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        // 持久会话是断线补投的前提，Config 已拒绝 cleanSession=true。
        options.setCleanSession(false);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setUserName(config.mqttUsername());
        options.setPassword(config.mqttPassword().toCharArray());

        LOG.log(Level.INFO, "连接 MQTT {0} clientId={1} username={2}",
                new Object[]{config.mqttUrl(), config.mqttClientId(), config.mqttUsername()});
        client.connect(options);
        // connectComplete 通常已经订阅过一次；这里再调一次是为了不依赖回调时序，
        // 重复订阅同样主题是幂等操作。
        subscribeAll();
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    private void subscribeAll() {
        List<String> topics = config.mqttTopics();
        int[] qos = new int[topics.size()];
        Arrays.fill(qos, 1);
        try {
            client.subscribe(topics.toArray(new String[0]), qos);
            LOG.log(Level.INFO, "已订阅 {0}", topics);
        } catch (MqttException e) {
            // SUBACK 0x80（ACL 与过滤器形状不一致）会走到这里。
            LOG.log(Level.SEVERE, "订阅失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                // 正常断开不触发 LWT，broker 会保留 retained 状态。
                client.disconnect(1000);
                LOG.info("已断开 MQTT");
            }
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "断开 MQTT 失败: {0}", e.getMessage());
        }
        try {
            client.close();
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "关闭 MQTT 客户端失败: {0}", e.getMessage());
        }
    }

    private final class Callback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            LOG.log(Level.INFO, reconnect ? "MQTT 重连成功: {0}" : "MQTT 连接成功: {0}", serverURI);
            subscribeAll();
        }

        @Override
        public void connectionLost(Throwable cause) {
            LOG.log(Level.WARNING, "MQTT 连接断开: {0}",
                    cause == null ? "未知原因" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            // 内容错误在 IngestService 内部吞掉；存储错误会冒泡到这里，
            // Paho 随后断开连接，broker 保留未确认消息并在重连后重投。
            ingest.onMessage(topic, message.getPayload(), message.isRetained());
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // 只订阅，不发布
        }
    }
}
