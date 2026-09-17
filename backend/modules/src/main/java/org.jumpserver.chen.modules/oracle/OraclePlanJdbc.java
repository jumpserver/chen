package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanLimits;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

final class OraclePlanJdbc {
    private OraclePlanJdbc() {
    }

    static Tracked<Statement> statement(PlanExecutionContext context, boolean useRequestDeadline)
            throws SQLException {
        if (useRequestDeadline) {
            context.throwIfCancelled();
        }
        return track(context.connection().createStatement(), context, useRequestDeadline);
    }

    static Tracked<PreparedStatement> preparedStatement(
            PlanExecutionContext context,
            String sql,
            boolean useRequestDeadline
    ) throws SQLException {
        if (useRequestDeadline) {
            context.throwIfCancelled();
        }
        return track(context.connection().prepareStatement(sql), context, useRequestDeadline);
    }

    private static <T extends Statement> Tracked<T> track(
            T statement,
            PlanExecutionContext context,
            boolean useRequestDeadline
    ) throws SQLException {
        context.registerStatement(statement);
        try {
            if (useRequestDeadline) {
                context.throwIfCancelled();
            }
            configureTimeout(statement, context, useRequestDeadline);
            return new Tracked<>(statement, context);
        } catch (SQLException e) {
            context.unregisterStatement(statement);
            try {
                statement.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private static void configureTimeout(
            Statement statement,
            PlanExecutionContext context,
            boolean useRequestDeadline
    ) throws SQLException {
        long remainingMillis = useRequestDeadline
                ? Math.max(1L, context.deadlineMillis() - System.currentTimeMillis())
                : PlanLimits.CLEANUP_TIMEOUT_MS;
        long seconds = Math.max(1L, (remainingMillis + 999L) / 1_000L);
        statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
    }

    static final class Tracked<T extends Statement> implements AutoCloseable {
        private final T statement;
        private final PlanExecutionContext context;

        private Tracked(T statement, PlanExecutionContext context) {
            this.statement = statement;
            this.context = context;
        }

        T statement() {
            return statement;
        }

        @Override
        public void close() throws SQLException {
            context.unregisterStatement(statement);
            statement.close();
        }
    }
}
