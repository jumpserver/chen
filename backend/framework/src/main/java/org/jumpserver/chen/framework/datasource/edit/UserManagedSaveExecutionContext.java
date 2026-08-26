package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.util.Objects;

public final class UserManagedSaveExecutionContext implements SaveExecutionContext {
    private final Connection connection;

    public UserManagedSaveExecutionContext(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Connection connection() {
        return this.connection;
    }

    @Override
    public TransactionMode transactionMode() {
        return TransactionMode.USER_MANAGED;
    }

    @Override
    public ConnectionOwnership connectionOwnership() {
        return ConnectionOwnership.QUERY_CONSOLE;
    }

    @Override
    public void close() {
        // The surrounding user transaction owns the connection lifecycle.
    }
}
