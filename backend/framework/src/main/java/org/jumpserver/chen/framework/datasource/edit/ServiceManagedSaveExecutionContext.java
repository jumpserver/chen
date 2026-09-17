package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class ServiceManagedSaveExecutionContext implements SaveExecutionContext {
    private final Connection connection;
    private final ConnectionOwnership connectionOwnership;

    public ServiceManagedSaveExecutionContext(Connection connection, ConnectionOwnership connectionOwnership) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.connectionOwnership = Objects.requireNonNull(connectionOwnership, "connectionOwnership");
    }

    @Override
    public Connection connection() {
        return this.connection;
    }

    @Override
    public TransactionMode transactionMode() {
        return TransactionMode.SERVICE_MANAGED;
    }

    @Override
    public ConnectionOwnership connectionOwnership() {
        return this.connectionOwnership;
    }

    @Override
    public void close() throws SQLException {
        if (this.connectionOwnership == ConnectionOwnership.EXECUTION_CONTEXT) {
            this.connection.close();
        }
    }
}
