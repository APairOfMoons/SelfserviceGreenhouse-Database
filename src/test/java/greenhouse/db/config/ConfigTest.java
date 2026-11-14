package greenhouse.db.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void loadsValuesFromFile(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                # 注释行
                mqtt.url = tcp://10.0.0.1:1883
                mqtt.password = secret
                db.dir = /srv/gh
                http.port = 9090
                api.tokens = a, b
                """);

        Config config = Config.load(file);

        assertEquals("tcp://10.0.0.1:1883", config.mqttUrl());
        assertEquals("secret", config.mqttPassword());
        assertEquals(9090, config.httpPort());
        assertEquals(2, config.apiTokens().size());
        assertEquals("b", config.apiTokens().get(1));
        assertEquals(3, config.mqttTopics().size());
        // 协议依赖持久会话，cleanSession 不开放
        assertFalse(config.mqttCleanSession());
    }

    @Test
    void missingPasswordIsRejected(@TempDir Path dir) throws IOException {
        Path file = write(dir, "api.tokens = t\n");
        assertThrows(ConfigException.class, () -> Config.load(file));
    }

    @Test
    void missingTokensIsRejected(@TempDir Path dir) throws IOException {
        Path file = write(dir, "mqtt.password = secret\n");
        assertThrows(ConfigException.class, () -> Config.load(file));
    }

    @Test
    void cleanSessionTrueIsRejected(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                mqtt.password = secret
                api.tokens = t
                mqtt.cleanSession = true
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> Config.load(file));
        assertTrue(e.getMessage().contains("cleanSession"));
    }

    @Test
    void badPortIsRejected(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                mqtt.password = secret
                api.tokens = t
                http.port = 70000
                """);
        assertThrows(ConfigException.class, () -> Config.load(file));
    }

    @Test
    void absentFileIsRejected() {
        assertThrows(ConfigException.class, () -> Config.load(Path.of("/no/such/gh-db.conf")));
    }

    @Test
    void systemPropertiesOverrideFile(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                mqtt.password = secret
                api.tokens = t
                http.port = 8080
                """);
        System.setProperty("ghdb.http.port", "18080");
        System.setProperty("ghdb.mqtt.password", "override");
        try {
            Config config = Config.load(file);
            assertEquals(18080, config.httpPort());
            assertEquals("override", config.mqttPassword());
        } finally {
            System.clearProperty("ghdb.http.port");
            System.clearProperty("ghdb.mqtt.password");
        }
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("gh-db.conf");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
