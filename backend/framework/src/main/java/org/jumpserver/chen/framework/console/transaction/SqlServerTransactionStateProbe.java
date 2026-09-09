package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class SqlServerTransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "com.microsoft.sqlserver.jdbc.SQLServerConnection";
    private static final String STATE_SQL = "SELECT XACT_STATE()";
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            if (!hasTransactionDescriptor(connection)) {
                return connection.getAutoCommit()
                        ? QueryTransactionState.AUTO_COMMIT
                        : QueryTransactionState.MANUAL_COMMIT_IDLE;
            }
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(STATE_SQL)) {
                if (!resultSet.next()) {
                    return QueryTransactionState.UNKNOWN;
                }
                return mapXactState(resultSet.getInt(1));
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    static QueryTransactionState mapXactState(int state) {
        return switch (state) {
            case 1 -> QueryTransactionState.TRANSACTION_ACTIVE;
            case -1 -> QueryTransactionState.TRANSACTION_FAILED;
            default -> QueryTransactionState.UNKNOWN;
        };
    }

    private static boolean hasTransactionDescriptor(Connection connection)
            throws ClassNotFoundException, SQLException, NoSuchMethodException,
            InvocationTargetException, IllegalAccessException {
        Object driverConnection = resolveDriverConnection(connection);
        Class<?> driverClass = Class.forName(
                DRIVER_CONNECTION,
                false,
                driverConnection.getClass().getClassLoader()
        );
        var method = driverClass.getDeclaredMethod("getTransactionDescriptor");
        method.setAccessible(true);
        byte[] descriptor = (byte[]) method.invoke(driverConnection);
        return hasTransactionDescriptor(descriptor);
    }

    /**
     * QueryConsole exposes Druid's statement connection, whose application classloader cannot
     * see the isolated Microsoft JDBC driver. Walk through wrappers until the driver connection
     * and its classloader are available.
     */
    static Object resolveDriverConnection(Connection connection) throws SQLException, ClassNotFoundException {
        if (connection == null) {
            throw new ClassNotFoundException(DRIVER_CONNECTION + " from null connection");
        }
        Connection current = connection;
        ClassNotFoundException lastNotFound = null;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            ClassLoader classLoader = current.getClass().getClassLoader();
            if (classLoader != null) {
                try {
                    Class<?> driverClass = Class.forName(DRIVER_CONNECTION, false, classLoader);
                    if (driverClass.isInstance(current)) {
                        return current;
                    }
                    if (current.isWrapperFor(driverClass)) {
                        return current.unwrap(driverClass);
                    }
                } catch (ClassNotFoundException e) {
                    lastNotFound = e;
                } catch (SQLException ignored) {
                    // This wrapper cannot unwrap with this loader's driver class. Peel and retry.
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
                DRIVER_CONNECTION + " from " + connection.getClass().getName()
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

    static boolean hasTransactionDescriptor(byte[] descriptor) {
        if (descriptor == null) {
            return false;
        }
        for (byte value : descriptor) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }
}
