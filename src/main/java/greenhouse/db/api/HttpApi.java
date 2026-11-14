package greenhouse.db.api;

import com.sun.net.httpserver.HttpServer;
import greenhouse.db.config.Config;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** {@code HttpServer} 的启停与线程池。业务逻辑都在 {@link Routes}。 */
public final class HttpApi implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpApi.class.getName());

    private final HttpServer server;
    private final ExecutorService pool;

    public HttpApi(Config config, Routes routes) throws IOException {
        InetSocketAddress address = config.httpBind() == null || config.httpBind().isBlank()
                || "*".equals(config.httpBind())
                ? new InetSocketAddress(config.httpPort())
                : new InetSocketAddress(config.httpBind(), config.httpPort());

        this.server = HttpServer.create(address, 16);

        int threads = config.httpThreads();
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "http-pool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(pool);
        this.server.createContext("/", routes);
    }

    public void start() {
        server.start();
        LOG.info("HTTP 接口已启动");
    }

    /** 实际监听端口（配置为 0 时由系统分配，测试用）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        pool.shutdownNow();
        LOG.info("HTTP 接口已停止");
    }
}
