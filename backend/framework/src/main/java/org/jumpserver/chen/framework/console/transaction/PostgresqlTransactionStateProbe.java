package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class PostgresqlTransactionStateProbe implements TransactionStateProbe {
    private static final String BASE_CONNECTION = "org.postgresql.core.BaseConnection";
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Object baseConnection = resolveBaseConnection(connection);
            Class<?> baseConnectionClass = baseConnection.getClass();
            ClassLoader driverClassLoader = baseConnectionClass.getClassLoader();
            Class<?> type = Class.forName(BASE_CONNECTION, false, driverClassLoader);
            if (!type.isInstance(baseConnection)) {
                baseConnection = ((Connection) baseConnection).unwrap(type);
            }
            Object transactionState = type.getMethod("getTransactionState").invoke(baseConnection);
            return map(String.valueOf(transactionState), connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    /**
     * Chen's QueryConsole "physical" connection is Druid's {@code DruidStatementConnection},
     * whose classloader cannot see the isolated PostgreSQL DriverClassLoader. Walk to the
     * raw {@code org.postgresql} connection and load BaseConnection from that loader.
     */
    static Object resolveBaseConnection(Connection connection) throws SQLException, ClassNotFoundException {
        if (connection == null) {
            throw new ClassNotFoundException(BASE_CONNECTION + " from null connection");
        }
        Connection current = connection;
        ClassNotFoundException lastNotFound = null;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            ClassLoader classLoader = current.getClass().getClassLoader();
            if (classLoader != null) {
                try {
                    Class<?> baseConnectionClass = Class.forName(BASE_CONNECTION, false, classLoader);
                    if (baseConnectionClass.isInstance(current)) {
                        return current;
                    }
                    if (current.isWrapperFor(baseConnectionClass)) {
                        return current.unwrap(baseConnectionClass);
                    }
                } catch (ClassNotFoundException e) {
                    lastNotFound = e;
                } catch (SQLException ignored) {
                    // This wrapper cannot unwrap with this loader's BaseConnection. Peel and retry.
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
                BASE_CONNECTION + " from " + connection.getClass().getName()
                        + " classLoader=" + classLoaderName(connection));
    }

    static Connection peel(Connection connection) {
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

    static String classLoaderName(Connection connection) {
        if (connection == null) {
            return "null";
        }
        ClassLoader classLoader = connection.getClass().getClassLoader();
        return classLoader == null ? "null" : classLoader.getClass().getName();
    }

    static QueryTransactionState map(String driverState, boolean autoCommit) {
        String state = driverState == null ? "" : driverState.trim();
        int dot = state.lastIndexOf('.');
        if (dot >= 0 && dot < state.length() - 1) {
            state = state.substring(dot + 1);
        }
        return switch (state) {
            case "OPEN" -> QueryTransactionState.TRANSACTION_ACTIVE;
            case "FAILED" -> QueryTransactionState.TRANSACTION_FAILED;
            case "IDLE" -> autoCommit
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.MANUAL_COMMIT_IDLE;
            default -> QueryTransactionState.UNKNOWN;
        };
    }
}
