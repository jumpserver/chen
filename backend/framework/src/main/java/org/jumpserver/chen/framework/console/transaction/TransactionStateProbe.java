package org.jumpserver.chen.framework.console.transaction;

import java.sql.Connection;

@FunctionalInterface
interface TransactionStateProbe {
    QueryTransactionState inspect(Connection connection);

    default QueryTransactionProbeResult inspectResult(Connection connection) {
        try {
            return QueryTransactionProbeResult.observed(this.inspect(connection));
        } catch (RuntimeException e) {
            return QueryTransactionProbeResult.failed();
        }
    }
}
