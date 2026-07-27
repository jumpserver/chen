package org.jumpserver.chen.framework.console.transaction;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

@Slf4j
final class DamengTransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "dm.jdbc.driver.DmdbConnection";
    private static final int STATE_MASK = 0x0FFF;
    private static final int NOT_STARTED = 0;
    private static final int COMMITTED = 32;
    private static final int ROLLED_BACK = 64;
    private static final String STATE_SQL =
            "SELECT STATUS FROM V$TRX WHERE SESS_ID = SESSID";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Class<?> driverClass = Class.forName(
                    DRIVER_CONNECTION,
                    false,
                    connection.getClass().getClassLoader()
            );
            Object driverConnection = driverClass.isInstance(connection)
                    ? connection
                    : connection.unwrap(driverClass);
            int transactionStatus = driverClass.getField("trxStatus").getInt(driverConnection);
            QueryTransactionState driverState = map(transactionStatus, connection.getAutoCommit());
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
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException |
                 SQLException | RuntimeException e) {
            log.debug("inspect Dameng transaction state failed", e);
            return QueryTransactionState.UNKNOWN;
        }
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
