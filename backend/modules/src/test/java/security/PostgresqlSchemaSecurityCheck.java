package security;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidConnectionHolder;
import com.alibaba.druid.pool.DruidPooledConnection;
import org.jumpserver.chen.modules.postgresql.PostgresqlActuator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import static security.MysqlIdentifierSecurityCheck.*;

/** Checks the actual actuator boundary and connection ownership, without a live DB. */
public class PostgresqlSchemaSecurityCheck {
    static void checkRealPoolWrapper() throws Exception {
        for (boolean bound : List.of(false, true)) {
            for (boolean fail : List.of(false, true)) {
                int[] calls = {0}, poolClosed = {0}, physicalClosed = {0};
                Connection physical = proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                    case "getAutoCommit" -> true;
                    case "isReadOnly", "isClosed" -> false;
                    case "getHoldability" -> 0;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "setSchema" -> {
                        calls[0]++;
                        if (fail) throw new SQLException("isolated driver failure");
                        if (!"O'Reilly".equals(a[0])) throw new AssertionError("driver input changed");
                        yield null;
                    }
                    case "close" -> { physicalClosed[0]++; yield null; }
                    default -> null;
                });
                try (DruidDataSource pool = new DruidDataSource()) {
                    pool.setDbType("postgresql");
                    DruidConnectionHolder holder = new DruidConnectionHolder(pool, physical, 0);
                    DruidPooledConnection wrapper = new DruidPooledConnection(holder) {
                        @Override public void close() { poolClosed[0]++; }
                    };
                    PostgresqlActuator base = new PostgresqlActuator(manager(DbType.postgresql, wrapper));
                    try {
                        (bound ? new PostgresqlActuator(base, wrapper) : base).changeSchema("database.O'Reilly");
                        if (fail) throw new AssertionError("driver failure swallowed");
                    } catch (SQLException e) { if (!fail) throw e; }
                    if (calls[0] != 1 || poolClosed[0] != (bound ? 0 : 1) || physicalClosed[0] != 0)
                        throw new AssertionError("Druid driver dispatch or pool ownership changed");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        for (boolean bound : List.of(false, true)) {
            for (String schema : List.of("normal", "MixedCase", "中文", "with space", "O'Reilly",
                    "schema'; SELECT 1; --", "back\\slash", "quote\"name")) {
                String[] actual = {null};
                int[] closed = {0};
                Connection connection = proxy(Connection.class, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setSchema": actual[0] = (String) a[0]; return null;
                        case "close": closed[0]++; return null;
                        default: throw new AssertionError("SQL construction or unexpected connection operation: " + m.getName());
                    }
                });
                PostgresqlActuator base = new PostgresqlActuator(manager(DbType.postgresql, connection));
                PostgresqlActuator actuator = bound ? new PostgresqlActuator(base, connection) : base;
                actuator.changeSchema("database." + schema);
                if (!schema.equals(actual[0])) throw new AssertionError("schema treated as SQL or identifier semantics changed");
                if (closed[0] != (bound ? 0 : 1)) throw new AssertionError("connection ownership changed");
            }
            int[] closed = {0};
            Connection failing = proxy(Connection.class, (p, m, a) -> {
                if (m.getName().equals("setSchema")) throw new SQLException("isolated failure");
                if (m.getName().equals("close")) { closed[0]++; return null; }
                throw new AssertionError(m.getName());
            });
            PostgresqlActuator base = new PostgresqlActuator(manager(DbType.postgresql, failing));
            try {
                (bound ? new PostgresqlActuator(base, failing) : base).changeSchema("database.normal");
                throw new AssertionError("driver error swallowed");
            } catch (SQLException expected) { }
            if (closed[0] != (bound ? 0 : 1)) throw new AssertionError("failed switch leaked or closed bound connection");
        }
        checkRealPoolWrapper();
        System.out.println("PostgreSQL schema values, real Druid wrapper dispatch, connection ownership and driver failures passed (no live PostgreSQL).");
    }
}
