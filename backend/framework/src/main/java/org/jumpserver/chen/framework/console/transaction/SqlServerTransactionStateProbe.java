package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;

final class SqlServerTransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "com.microsoft.sqlserver.jdbc.SQLServerConnection";
    private static final String STATE_SQL = "SELECT XACT_STATE()";

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
        Class<?> driverClass = Class.forName(DRIVER_CONNECTION, false, connection.getClass().getClassLoader());
        Object driverConnection = driverClass.isInstance(connection)
                ? connection
                : connection.unwrap(driverClass);
        var method = driverClass.getDeclaredMethod("getTransactionDescriptor");
        method.setAccessible(true);
        byte[] descriptor = (byte[]) method.invoke(driverConnection);
        return hasTransactionDescriptor(descriptor);
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
