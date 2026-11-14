package greenhouse.db.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * java.util.logging 的初始化：单行格式、UTC 时间、输出到 stderr。
 * <p>
 * 部署时 stdout/stderr 都进 journald，所以不做文件滚动。
 */
public final class Logs {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String PREFIX = "greenhouse.db.";

    private Logs() {
    }

    /** 重置全局配置并装上唯一的控制台 handler。重复调用安全。 */
    public static void init() {
        LogManager.getLogManager().reset();

        Handler console = new ConsoleHandler();
        console.setLevel(Level.ALL);
        console.setFormatter(new LineFormatter());

        Logger root = Logger.getLogger("");
        for (Handler old : root.getHandlers()) {
            root.removeHandler(old);
            old.close();
        }
        root.addHandler(console);
        root.setLevel(Level.INFO);

        // Paho/第三方库降噪：连接断开由我们自己记一条，不要它们每次重连刷两行。
        Logger.getLogger("org.eclipse.paho").setLevel(Level.WARNING);
    }

    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(TS.format(Instant.ofEpochMilli(r.getMillis())))
                    .append(' ')
                    .append(r.getLevel().getName())
                    .append(" [")
                    .append(Thread.currentThread().getName())
                    .append("] ")
                    .append(shortName(r.getLoggerName()))
                    .append(" - ")
                    .append(formatMessage(r))
                    .append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }

        private static String shortName(String loggerName) {
            if (loggerName == null) {
                return "?";
            }
            return loggerName.startsWith(PREFIX) ? loggerName.substring(PREFIX.length()) : loggerName;
        }
    }
}
