package greenhouse.db;

import greenhouse.db.api.HttpApi;
import greenhouse.db.api.Routes;
import greenhouse.db.config.Config;
import greenhouse.db.config.ConfigException;
import greenhouse.db.mqtt.IngestService;
import greenhouse.db.mqtt.IngestStats;
import greenhouse.db.mqtt.MqttIngest;
import greenhouse.db.store.AlarmRepo;
import greenhouse.db.store.Database;
import greenhouse.db.store.DeviceRepo;
import greenhouse.db.store.Schema;
import greenhouse.db.store.StatusRepo;
import greenhouse.db.store.TelemetryRepo;
import greenhouse.db.util.Closeables;
import greenhouse.db.util.Logs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 组装与生命周期。这里不写业务逻辑。
 * <p>
 * 启动顺序：迁移 DDL → 起 HTTP → 连 MQTT。先起 HTTP 是为了让"库已就绪但接口没起"
 * 的窗口尽量小；MQTT 最后连，避免连上后立刻因库没准备好而丢消息。
 */
public final class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final String DEFAULT_CONFIG = "/etc/gh-db/gh-db.conf";

    private App() {
    }

    public static void main(String[] args) {
        Logs.init();
        try {
            run(args);
        } catch (ConfigException e) {
            LOG.log(Level.SEVERE, "配置错误: {0}", e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "启动失败", e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path configPath = resolveConfigPath(args);
        Config config = Config.load(configPath);
        LOG.log(Level.INFO, "配置文件: {0}", configPath == null ? "（未使用，全部取默认值/系统属性）" : configPath);

        Database db = Database.open(config.dbDir(), config.dbName());
        HttpApi http = null;
        MqttIngest mqtt = null;
        try {
            Schema.migrate(db);

            DeviceRepo devices = new DeviceRepo(db);
            TelemetryRepo telemetry = new TelemetryRepo(db);
            AlarmRepo alarms = new AlarmRepo(db);
            StatusRepo statuses = new StatusRepo(db);
            IngestStats stats = new IngestStats();
            IngestService ingest = new IngestService(db, devices, telemetry, alarms, statuses, stats);

            // MqttIngest 在 Routes 之后才建，用数组把引用转交给健康检查。
            MqttIngest[] mqttRef = new MqttIngest[1];
            Routes routes = new Routes(db, devices, telemetry, alarms, statuses, config.apiTokens(), stats,
                    () -> {
                        MqttIngest current = mqttRef[0];
                        return current != null && current.isConnected();
                    });

            http = new HttpApi(config, routes);
            http.start();
            // port 用字符串传：MessageFormat 会把数字格式化成 "18,080"。
            LOG.log(Level.INFO, "HTTP 监听 {0}:{1}",
                    new Object[]{config.httpBind(), String.valueOf(http.port())});

            mqtt = new MqttIngest(config, ingest);
            mqttRef[0] = mqtt;
            mqtt.start();
        } catch (Exception e) {
            Closeables.quietly(mqtt);
            Closeables.quietly(http);
            Closeables.quietly(db);
            throw e;
        }

        final HttpApi finalHttp = http;
        final MqttIngest finalMqtt = mqtt;
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("收到停机信号，开始关闭");
            Closeables.quietly(finalMqtt);
            Closeables.quietly(finalHttp);
            Closeables.quietly(db);
            stopped.countDown();
            LOG.info("已停机");
        }, "shutdown"));

        LOG.info("启动完成");
        stopped.await();
    }

    /** 支持 {@code --config <path>} 与 {@code --config=<path>}。 */
    private static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = null;
            if ("--config".equals(arg) && i + 1 < args.length) {
                value = args[++i];
            } else if (arg.startsWith("--config=")) {
                value = arg.substring("--config=".length());
            }
            if (value != null) {
                Path path = Paths.get(value);
                if (!Files.isRegularFile(path)) {
                    throw new ConfigException("指定的配置文件不存在: " + path.toAbsolutePath());
                }
                return path;
            }
        }
        Path defaultPath = Paths.get(DEFAULT_CONFIG);
        return Files.isRegularFile(defaultPath) ? defaultPath : null;
    }
}
