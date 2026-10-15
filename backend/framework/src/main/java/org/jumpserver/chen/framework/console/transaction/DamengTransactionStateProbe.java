package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

final class DamengTransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "dm.jdbc.driver.DmdbConnection";
    private static final int MAX_UNWRAP_DEPTH = 8;
    private static final int STATE_MASK = 0x0FFF;
    private static final int NOT_STARTED = 0;
    private static final int COMMITTED = 32;
    private static final int ROLLED_BACK = 64;
    private static final String STATE_SQL =
            "SELECT STATUS FROM V$TRX WHERE SESS_ID = SESSID";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Object driverConnection = resolveDriverConnection(connection);
            Class<?> driverClass = Class.forName(
                    DRIVER_CONNECTION,
                    false,
                    driverConnection.getClass().getClassLoader()
            );
            int transactionStatus = driverClass.getField("trxStatus").getInt(driverConnection);
            boolean autoCommit = connection.getAutoCommit();
            boolean transactionFinished = (boolean) driverClass
                    .getMethod("getTransFinish")
                    .invoke(driverConnection);
            if (transactionFinished) {
                // DM 8.1.3.140 leaves trxStatus at ACTIVE after commit and marks completion here.
                // Avoid querying V$TRX in that state: the query itself can start a transaction.
                return map(COMMITTED, autoCommit);
            }
            QueryTransactionState driverState = map(transactionStatus, autoCommit);
            if (driverState != QueryTransactionState.TRANSACTION_ACTIVE) {
                return driverState;
            }
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(STATE_SQL)) {
                if (!resultSet.next()) {
                    return QueryTransactionState.UNKNOWN;
                }
                return mapServerStatus(resultSet.getString(1));
            }
        } catch (ReflectiveOperationException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    /**
     * QueryConsole can expose Druid's statement connection while the Dameng driver lives in an
     * isolated DriverClassLoader. Peel wrappers before loading DmdbConnection so reflection uses
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

    static QueryTransactionState map(int transactionStatus, boolean autoCommit) {
        int state = transactionStatus & STATE_MASK;
        if (state == NOT_STARTED || state == COMMITTED || state == ROLLED_BACK) {
            return autoCommit
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.MANUAL_COMMIT_IDLE;
        }
        return QueryTransactionState.TRANSACTION_ACTIVE;
    }

    static QueryTransactionState mapServerStatus(String status) {
        if (status == null) {
            return QueryTransactionState.UNKNOWN;
        }
        return switch (status.toUpperCase(Locale.ROOT).trim()) {
            case "ACTIVE", "LOCK WAIT" -> QueryTransactionState.TRANSACTION_ACTIVE;
            case "ROLLING", "ROLLING_SUSPEND" -> QueryTransactionState.TRANSACTION_FAILED;
            default -> QueryTransactionState.UNKNOWN;
        };
    }
}
