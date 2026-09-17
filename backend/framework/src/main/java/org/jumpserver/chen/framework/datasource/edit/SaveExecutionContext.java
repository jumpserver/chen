package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.sql.SQLException;

public interface SaveExecutionContext extends AutoCloseable {
    Connection connection() throws SQLException;

    TransactionMode transactionMode();

    ConnectionOwnership connectionOwnership();

    @Override
    void close() throws SQLException;
}
