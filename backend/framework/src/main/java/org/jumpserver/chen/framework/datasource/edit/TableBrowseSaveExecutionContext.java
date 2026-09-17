package org.jumpserver.chen.framework.datasource.edit;

import org.jumpserver.chen.framework.datasource.ConnectionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class TableBrowseSaveExecutionContext implements SaveExecutionContext {
    private final ConnectionManager connectionManager;
    private Connection connection;

    public TableBrowseSaveExecutionContext(ConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    @Override
    public Connection connection() throws SQLException {
        if (this.connection == null) {
            this.connection = this.connectionManager.getConnection();
        }
        return this.connection;
    }

    @Override
    public TransactionMode transactionMode() {
        return TransactionMode.SERVICE_MANAGED;
    }

    @Override
    public ConnectionOwnership connectionOwnership() {
        return ConnectionOwnership.EXECUTION_CONTEXT;
    }

    @Override
    public void close() throws SQLException {
        if (this.connection != null) {
            this.connection.close();
        }
    }
}
