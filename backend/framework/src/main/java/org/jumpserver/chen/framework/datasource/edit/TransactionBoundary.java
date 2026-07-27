package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.sql.SQLException;

interface TransactionBoundary {
    <T> T execute(Connection connection, TableChangesPlan plan, TransactionWork<T> work) throws SQLException;
}

