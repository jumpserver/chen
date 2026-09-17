package org.jumpserver.chen.framework.datasource.edit;

import java.sql.SQLException;

@FunctionalInterface
interface TransactionWork<T> {
    T execute() throws SQLException;
}

