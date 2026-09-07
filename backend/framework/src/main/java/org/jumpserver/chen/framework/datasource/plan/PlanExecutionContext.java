package org.jumpserver.chen.framework.datasource.plan;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public interface PlanExecutionContext {
    Connection connection();

    PlanTransactionState transactionState();

    boolean transactionProbeFailed();

    default String transactionProbeDetail() {
        return null;
    }

    void registerStatement(Statement statement);

    void unregisterStatement(Statement statement);

    boolean isCancelled();

    long deadlineMillis();

    int maxRawBytes();

    int maxNodes();

    int maxDepth();

    String serverVersion();

    void throwIfCancelled() throws SQLException;
}
