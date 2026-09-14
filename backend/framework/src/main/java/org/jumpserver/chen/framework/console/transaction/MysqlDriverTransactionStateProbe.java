package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class MysqlDriverTransactionStateProbe implements TransactionStateProbe {
    private static final String JDBC_CONNECTION = "com.mysql.cj.jdbc.JdbcConnection";
    private static final String SESSION = "com.mysql.cj.Session";
    private static final String SERVER_SESSION = "com.mysql.cj.protocol.ServerSession";
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Object jdbcConnection = resolveDriverConnection(connection);
            ClassLoader classLoader = jdbcConnection.getClass().getClassLoader();
            Class<?> jdbcConnectionClass = Class.forName(JDBC_CONNECTION, false, classLoader);
            Object session = jdbcConnectionClass.getMethod("getSession").invoke(jdbcConnection);
            Class<?> sessionClass = Class.forName(SESSION, false, classLoader);
            Object serverSession = sessionClass.getMethod("getServerSession").invoke(session);
            Class<?> serverSessionClass = Class.forName(SERVER_SESSION, false, classLoader);
            boolean inTransaction = (boolean) serverSessionClass
                    .getMethod("inTransactionOnServer")
                    .invoke(serverSession);
            return map(inTransaction, connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    /**
     * QueryConsole can expose Druid's statement connection while Connector/J lives in an
     * isolated DriverClassLoader. Peel wrappers before loading the driver interface so every
     * reflected Connector/J type comes from the physical connection's loader.
     */
    static Object resolveDriverConnection(Connection connection) throws SQLException, ClassNotFoundException {
        if (connection == null) {
            throw new ClassNotFoundException(JDBC_CONNECTION + " from null connection");
        }
        Connection current = connection;
        ClassNotFoundException lastNotFound = null;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            ClassLoader classLoader = current.getClass().getClassLoader();
            if (classLoader != null) {
                try {
                    Class<?> jdbcConnectionClass = Class.forName(JDBC_CONNECTION, false, classLoader);
                    if (jdbcConnectionClass.isInstance(current)) {
                        return current;
                    }
                    if (current.isWrapperFor(jdbcConnectionClass)) {
                        return current.unwrap(jdbcConnectionClass);
                    }
                } catch (ClassNotFoundException e) {
                    lastNotFound = e;
                } catch (SQLException ignored) {
                    // This wrapper cannot unwrap with the driver class visible to its loader.
                }
            }
            Connection next = peel(current);
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        throw lastNotFound != null
                ? lastNotFound
                : new ClassNotFoundException(
                JDBC_CONNECTION + " from " + connection.getClass().getName()
                        + " classLoader=" + classLoaderName(connection));
    }

    private static Connection peel(Connection connection) {
        Connection viaGetter = invokeConnectionGetter(connection, "getConnection");
        if (viaGetter != null) {
            return viaGetter;
        }
        Connection viaPhysical = invokeConnectionGetter(connection, "getPhysicalConnection");
        if (viaPhysical != null) {
            return viaPhysical;
        }
        try {
            Connection unwrapped = connection.unwrap(Connection.class);
            if (unwrapped != null && unwrapped != connection) {
                return unwrapped;
            }
        } catch (SQLException ignored) {
        }
        return connection;
    }

    private static Connection invokeConnectionGetter(Connection connection, String methodName) {
        try {
            Method method = connection.getClass().getMethod(methodName);
            Object value = method.invoke(connection);
            if (value instanceof Connection next && next != connection) {
                return next;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static String classLoaderName(Connection connection) {
        ClassLoader classLoader = connection.getClass().getClassLoader();
        return classLoader == null ? "null" : classLoader.getClass().getName();
    }

    static QueryTransactionState map(boolean inTransaction, boolean autoCommit) {
        if (inTransaction) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        return autoCommit
                ? QueryTransactionState.AUTO_COMMIT
                : QueryTransactionState.MANUAL_COMMIT_IDLE;
    }
}
