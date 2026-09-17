package org.jumpserver.chen.framework.console.transaction;

import java.sql.Connection;
import java.sql.SQLException;

final class MysqlTransactionStateProbe implements TransactionStateProbe {
    private static final String STATE_SQL =
            "SELECT @@session.autocommit, @@session.in_transaction";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(STATE_SQL)) {
            if (!resultSet.next()) {
                return QueryTransactionState.UNKNOWN;
            }
            if (resultSet.getBoolean(2)) {
                return QueryTransactionState.TRANSACTION_ACTIVE;
            }
            return resultSet.getBoolean(1)
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.MANUAL_COMMIT_IDLE;
        } catch (SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }
}
