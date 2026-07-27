package org.jumpserver.chen.framework.console.transaction;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
final class OracleTransactionStateProbe implements TransactionStateProbe {
    private static final String STATE_SQL =
            "SELECT DBMS_TRANSACTION.LOCAL_TRANSACTION_ID(FALSE) FROM DUAL";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(STATE_SQL)) {
            if (!resultSet.next()) {
                return QueryTransactionState.UNKNOWN;
            }
            String transactionId = resultSet.getString(1);
            if (transactionId != null && !transactionId.isBlank()) {
                return QueryTransactionState.TRANSACTION_ACTIVE;
            }
            return connection.getAutoCommit()
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.MANUAL_COMMIT_IDLE;
        } catch (SQLException | RuntimeException e) {
            log.debug("inspect Oracle transaction state failed", e);
            return QueryTransactionState.UNKNOWN;
        }
    }
}
