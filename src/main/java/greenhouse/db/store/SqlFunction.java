package greenhouse.db.store;

import java.sql.Connection;
import java.sql.SQLException;

/** 在事务里执行的一段工作。异常即回滚。 */
@FunctionalInterface
public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
}
