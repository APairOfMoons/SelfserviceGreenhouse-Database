package greenhouse.db.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 运行配置。
 * <p>
 * 优先级：系统属性 {@code -Dghdb.<key>=<value>} &gt; 配置文件 &gt; 内置默认值。
 * 键名与 {@code STRUCTURE.md} 第 6 节一一对应。
 */
public record Config(
        String mqttUrl,
        String mqttClientId,
        String mqttUsername,
        String mqttPassword,
        boolean mqttCleanSession,
        List<String> mqttTopics,
        Path dbDir,
        String dbName,
        String httpBind,
        int httpPort,
        int httpThreads,
        List<String> apiTokens) {

    private static final String SYS_PREFIX = "ghdb.";
    private static final String DEFAULT_TOPICS = "gh/+/telemetry,gh/+/alarm/+,gh/+/status";

    /** 按默认值构造，仅用于测试。 */
    public static Config defaults() {
        return new Config(
                "tcp://127.0.0.1:1883",
                "gh-db",
                "dataserver",
                "test-password",
                false,
                List.of("gh/+/telemetry", "gh/+/alarm/+", "gh/+/status"),
                Paths.get("data"),
                "ghdb",
                "127.0.0.1",
                0,
                2,
                List.of("test-token"));
    }

    /**
     * 从配置文件加载。
     *
     * @param file 配置文件；为 null 表示只用系统属性与默认值
     */
    public static Config load(Path file) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                throw new ConfigException("配置文件不存在: " + file);
            }
            loadFile(file, raw);
        }

        // -D 覆盖：即使文件里没写这个键也生效，方便临时改端口调试。
        Properties sys = System.getProperties();
        for (String name : sys.stringPropertyNames()) {
            if (name.startsWith(SYS_PREFIX)) {
                raw.put(name.substring(SYS_PREFIX.length()), sys.getProperty(name));
            }
        }

        String mqttPassword = get(raw, "mqtt.password", null);
        if (mqttPassword == null || mqttPassword.isBlank()) {
            throw new ConfigException("缺少 mqtt.password");
        }

        List<String> tokens = splitList(get(raw, "api.tokens", null));
        if (tokens.isEmpty()) {
            throw new ConfigException("缺少 api.tokens（客户端 Bearer 校验用，逗号分隔）");
        }

        boolean cleanSession = Boolean.parseBoolean(get(raw, "mqtt.cleanSession", "false"));
        if (cleanSession) {
            // cleanSession=true 会让 broker 在断线时丢掉会话，重启期间的消息直接消失。
            // 这里不给开关：协议 8.1 依赖持久会话补投。
            throw new ConfigException("mqtt.cleanSession 必须为 false（持久会话是断线补投的前提）");
        }

        Config config = new Config(
                get(raw, "mqtt.url", "tcp://127.0.0.1:1883"),
                get(raw, "mqtt.clientId", "gh-db"),
                get(raw, "mqtt.username", "dataserver"),
                mqttPassword,
                false,
                splitList(get(raw, "mqtt.topics", DEFAULT_TOPICS)),
                Paths.get(get(raw, "db.dir", "/var/lib/gh-db")),
                get(raw, "db.name", "ghdb"),
                get(raw, "http.bind", "0.0.0.0"),
                parseInt(raw, "http.port", 8080, 1, 65535),
                parseInt(raw, "http.threads", 6, 1, 256),
                tokens);

        if (config.mqttTopics().isEmpty()) {
            throw new ConfigException("mqtt.topics 不能为空");
        }
        return config;
    }

    private static void loadFile(Path file, Map<String, String> into) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("读配置文件失败: " + file + " (" + e.getMessage() + ")", e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new ConfigException("配置文件第 " + (i + 1) + " 行缺少 '=': " + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            // 支持 "值 # 注释"
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            into.put(key, value);
        }
    }

    private static String get(Map<String, String> raw, String key, String dflt) {
        String v = raw.get(key);
        return v == null ? dflt : v;
    }

    private static int parseInt(Map<String, String> raw, String key, int dflt, int min, int max) {
        String v = raw.get(key);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " 不是整数: " + v);
        }
        if (parsed < min || parsed > max) {
            throw new ConfigException(key + " 超出范围 " + min + ".." + max + ": " + parsed);
        }
        return parsed;
    }

    private static List<String> splitList(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }
}
