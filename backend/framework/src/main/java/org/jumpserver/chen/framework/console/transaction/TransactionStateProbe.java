package org.jumpserver.chen.framework.console.transaction;

import java.sql.Connection;

@FunctionalInterface
interface TransactionStateProbe {
    QueryTransactionState inspect(Connection connection);
}
