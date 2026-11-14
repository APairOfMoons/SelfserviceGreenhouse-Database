package greenhouse.db.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * H2 文件库的打开、事务边界与关闭。
 * <p>
 * 只有一个写者（Paho 回调线程），读侧是 HTTP 线程池，所以用连接池而不是单连接。
 * {@code WRITE_DELAY=0} 让 COMMIT 直接落盘——写入频率是分钟级，代价可以忽略，
 * 换来"PUBACK 发出时数据已经持久化"这个性质（PLAN 3 节）。
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    private final String url;
    private final JdbcConnectionPool pool;

    private Database(String url, int maxConnections) {
        this.url = url;
        this.pool = JdbcConnectionPool.create(url, "sa", "");
        this.pool.setMaxConnections(maxConnections);
        // 拿不到连接时尽快失败，而不是让 Paho 回调线程一直挂着。
        this.pool.setLoginTimeout(10);
    }

    /** 打开（必要时创建）数据目录下的单文件库。 */
    public static Database open(Path dir, String name) {
        Path abs = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(abs);
        } catch (IOException e) {
            throw new StoreException("无法创建数据目录: " + abs, e);
        }
        String path = abs.resolve(name).toString();
        String url = "jdbc:h2:" + path
                + ";WRITE_DELAY=0"
                + ";LOCK_TIMEOUT=10000"
                + ";DB_CLOSE_ON_EXIT=FALSE";
        LOG.log(Level.INFO, "打开数据库 {0}", path);
        return new Database(url, 8);
    }

    /** 内存库，测试专用。同名库在 JVM 内共享。 */
    public static Database openMemory(String name) {
        return new Database("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", 4);
    }

    public String url() {
        return url;
    }

    /**
     * 在一个事务里执行；正常返回即提交，抛异常即回滚。
     * <p>
     * 只读查询也走这里：自动提交会让每条 SELECT 单开事务，反而更啰嗦。
     */
    public <T> T tx(SqlFunction<T> work) {
        try (Connection conn = pool.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e instanceof StoreException se ? se : new StoreException("事务失败: " + e.getMessage(), e);
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // 连接即将归还，失败也无意义
                }
            }
        } catch (SQLException e) {
            throw new StoreException("获取数据库连接失败: " + e.getMessage(), e);
        }
    }

    /** 在线备份到指定文件（H2 的 BACKUP TO，无需停服务）。 */
    public void backup(Path target) {
        Path abs = target.toAbsolutePath().normalize();
        try {
            Files.createDirectories(abs.getParent());
        } catch (IOException e) {
            throw new StoreException("无法创建备份目录: " + abs.getParent(), e);
        }
        String sql = "BACKUP TO '" + abs.toString().replace("'", "''") + "'";
        try (Connection conn = pool.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new StoreException("备份失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        LOG.info("关闭数据库");
        pool.dispose();
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "回滚失败: {0}", e.getMessage());
        }
    }
}
