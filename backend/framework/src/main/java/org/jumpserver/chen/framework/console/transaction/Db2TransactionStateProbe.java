package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class Db2TransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "com.ibm.db2.jcc.DB2Connection";
    private static final int MAX_UNWRAP_DEPTH = 8;

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Object driverConnection = resolveDriverConnection(connection);
            Class<?> driverClass = Class.forName(
                    DRIVER_CONNECTION,
                    false,
                    driverConnection.getClass().getClassLoader()
            );
            boolean inUnitOfWork = (boolean) driverClass
                    .getMethod("isInDB2UnitOfWork")
                    .invoke(driverConnection);
            return map(inUnitOfWork, connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    /**
     * QueryConsole can expose Druid's statement connection while JCC lives in an isolated
     * DriverClassLoader. Peel wrappers before loading the driver interface so reflection uses
     * the same loader as the physical connection.
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

    static QueryTransactionState map(boolean inUnitOfWork, boolean autoCommit) {
        if (inUnitOfWork) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        return autoCommit
                ? QueryTransactionState.AUTO_COMMIT
                : QueryTransactionState.MANUAL_COMMIT_IDLE;
    }
}
