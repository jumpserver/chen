package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;

final class PostgresqlTransactionStateProbe implements TransactionStateProbe {
    private static final String BASE_CONNECTION = "org.postgresql.core.BaseConnection";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            ClassLoader classLoader = connection.getClass().getClassLoader();
            Class<?> baseConnectionClass = Class.forName(BASE_CONNECTION, false, classLoader);
            Object baseConnection = baseConnectionClass.isInstance(connection)
                    ? connection
                    : connection.unwrap(baseConnectionClass);
            Object transactionState = baseConnectionClass
                    .getMethod("getTransactionState")
                    .invoke(baseConnection);

            return map(transactionState.toString(), connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    static QueryTransactionState map(String driverState, boolean autoCommit) {
        return switch (driverState) {
            case "OPEN" -> QueryTransactionState.TRANSACTION_ACTIVE;
            case "FAILED" -> QueryTransactionState.TRANSACTION_FAILED;
            case "IDLE" -> autoCommit
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.MANUAL_COMMIT_IDLE;
            default -> QueryTransactionState.UNKNOWN;
        };
    }
}
