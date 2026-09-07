package org.jumpserver.chen.framework.console.plan;

import org.jumpserver.chen.framework.console.transaction.QueryTransactionProbeResult;
import org.jumpserver.chen.framework.console.transaction.QueryTransactionState;
import org.jumpserver.chen.framework.console.transaction.QueryTransactionStateInspector;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanLimits;
import org.jumpserver.chen.framework.datasource.plan.PlanTransactionState;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class QueryConsolePlanContext implements PlanExecutionContext {
    private final Connection connection;
    private final QueryTransactionStateInspector inspector;
    private final AtomicBoolean cancelled;
    private final AtomicReference<Statement> currentStatement = new AtomicReference<>();
    private final long deadlineMillis;
    private final int maxRawBytes;
    private final int maxNodes;
    private final int maxDepth;
    private final String serverVersion;
    private QueryTransactionProbeResult probeSnapshot;

    public QueryConsolePlanContext(
            Connection connection,
            QueryTransactionStateInspector inspector,
            AtomicBoolean cancelled,
            long deadlineMillis,
            String serverVersion
    ) {
        this.connection = connection;
        this.inspector = inspector;
        this.cancelled = cancelled == null ? new AtomicBoolean(false) : cancelled;
        this.deadlineMillis = deadlineMillis;
        this.maxRawBytes = PlanLimits.MAX_RAW_BYTES;
        this.maxNodes = PlanLimits.MAX_NODES;
        this.maxDepth = PlanLimits.MAX_DEPTH;
        this.serverVersion = serverVersion;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public PlanTransactionState transactionState() {
        return map(snapshot().state());
    }

    @Override
    public boolean transactionProbeFailed() {
        return snapshot().probeFailed();
    }

    @Override
    public String transactionProbeDetail() {
        return snapshot().detail();
    }

    @Override
    public void registerStatement(Statement statement) {
        currentStatement.set(statement);
    }

    @Override
    public void unregisterStatement(Statement statement) {
        currentStatement.compareAndSet(statement, null);
    }

    public void cancelCurrent() throws SQLException {
        cancelled.set(true);
        Statement statement = currentStatement.get();
        if (statement != null) {
            statement.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get() || System.currentTimeMillis() >= deadlineMillis;
    }

    @Override
    public void throwIfCancelled() throws SQLException {
        if (cancelled.get()) {
            throw new SQLException("Execution plan request cancelled", "57014");
        }
        if (System.currentTimeMillis() >= deadlineMillis) {
            throw new SQLTimeoutException("Execution plan request timed out");
        }
    }

    @Override
    public long deadlineMillis() {
        return deadlineMillis;
    }

    @Override
    public int maxRawBytes() {
        return maxRawBytes;
    }

    @Override
    public int maxNodes() {
        return maxNodes;
    }

    @Override
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    private QueryTransactionProbeResult snapshot() {
        if (probeSnapshot == null) {
            if (inspector == null) {
                probeSnapshot = new QueryTransactionProbeResult(QueryTransactionState.UNKNOWN, true, "inspector not attached");
            } else {
                probeSnapshot = inspector.probeNow();
            }
        }
        return probeSnapshot;
    }

    private static PlanTransactionState map(QueryTransactionState state) {
        if (state == null) {
            return PlanTransactionState.UNKNOWN;
        }
        return switch (state) {
            case AUTO_COMMIT -> PlanTransactionState.AUTO_COMMIT;
            case MANUAL_COMMIT_IDLE -> PlanTransactionState.MANUAL_COMMIT_IDLE;
            case TRANSACTION_ACTIVE -> PlanTransactionState.TRANSACTION_ACTIVE;
            case TRANSACTION_FAILED -> PlanTransactionState.TRANSACTION_FAILED;
            case UNKNOWN -> PlanTransactionState.UNKNOWN;
        };
    }
}
