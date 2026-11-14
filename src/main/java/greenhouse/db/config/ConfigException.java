package greenhouse.db.config;

/** 配置缺失或非法。启动阶段抛出，由 {@code App} 转成退出码 1。 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
