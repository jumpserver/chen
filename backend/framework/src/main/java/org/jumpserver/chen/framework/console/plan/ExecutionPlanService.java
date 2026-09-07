package org.jumpserver.chen.framework.console.plan;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalyzer;
import org.jumpserver.chen.framework.datasource.plan.ConnectionInvalidatedException;
import org.jumpserver.chen.framework.datasource.plan.DialectPlanResult;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlan;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanCapabilities;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDatabase;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanEffects;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanLimits;
import org.jumpserver.chen.framework.datasource.plan.PlanMode;
import org.jumpserver.chen.framework.datasource.plan.PlanStatus;
import org.jumpserver.chen.framework.datasource.plan.UnsupportedExecutionPlanDialect;

import java.sql.SQLException;
import java.util.List;

public final class ExecutionPlanService {
    public ExecutionPlan explainEstimated(
            Datasource datasource,
            PlanExecutionContext context,
            String sql,
            String requestId
    ) throws SQLException {
        ExecutionPlanDialect dialect = datasource == null
                ? UnsupportedExecutionPlanDialect.getInstance()
                : datasource.getExecutionPlanDialect();
        PlanDatabase database = dialect.database();
        ExecutionPlanCapabilities capabilities = dialect.capabilities();
        String safeSql = sql == null ? "" : sql;
        String safeRequestId = requestId == null ? "" : requestId;

        if (safeSql.length() > PlanLimits.MAX_SQL_CHARS) {
            return failure(
                    safeRequestId,
                    database,
                    safeSql,
                    capabilities,
                    PlanStatus.UNSUPPORTED_STATEMENT,
                    PlanCodes.PLAN_LIMIT_REACHED,
                    "SQL exceeds the execution plan size limit"
            );
        }

        DbType dbType = datasource == null ? null : datasource.getDruidDbType();
        SqlStatementAnalysis analysis = dbType == null
                ? SqlStatementAnalyzer.analyze(com.alibaba.druid.DbType.other, safeSql)
                : SqlStatementAnalyzer.analyze(dbType, safeSql);

        if (!analysis.estimable()) {
            String code = analysis.rejectionCodes().isEmpty()
                    ? PlanCodes.UNSUPPORTED_STATEMENT
                    : analysis.rejectionCodes().get(0);
            return failure(
                    safeRequestId,
                    database,
                    analysis.sql().isEmpty() ? safeSql : analysis.sql(),
                    capabilities,
                    PlanStatus.UNSUPPORTED_STATEMENT,
                    code,
                    rejectionMessage(code)
            );
        }

        if (!capabilities.supported()) {
            return failure(
                    safeRequestId,
                    database,
                    analysis.sql(),
                    capabilities,
                    PlanStatus.UNSUPPORTED_DATABASE,
                    PlanCodes.UNSUPPORTED_DATABASE,
                    "Execution plan is not implemented for this datasource"
            );
        }

        try {
            DialectPlanResult result = dialect.explainEstimated(context, analysis);
            return ExecutionPlan.fromDialect(safeRequestId, database, PlanMode.ESTIMATED, analysis.sql(), result);
        } catch (ConnectionInvalidatedException e) {
            throw e;
        } catch (SQLException e) {
            if (isCancelled(e) || (context != null && context.isCancelled())) {
                return failure(
                        safeRequestId,
                        database,
                        analysis.sql(),
                        capabilities,
                        PlanStatus.CANCELLED,
                        PlanCodes.CANCELLED,
                        e.getMessage() == null ? "Execution plan request cancelled" : e.getMessage(),
                        e
                );
            }
            return failure(
                    safeRequestId,
                    database,
                    analysis.sql(),
                    capabilities,
                    PlanStatus.ERROR,
                    PlanCodes.SQL_ERROR,
                    e.getMessage() == null ? "Failed to estimate execution plan" : e.getMessage(),
                    e
            );
        }
    }

    static String rejectionMessage(String code) {
        if (code == null || code.isBlank()) {
            return "This statement cannot be estimated";
        }
        return switch (code) {
            case SqlStatementAnalyzer.PARSE_FAILED ->
                    "Chen cannot parse this SQL for an estimated plan";
            case SqlStatementAnalyzer.MULTI_STATEMENT ->
                    "Multiple SQL statements cannot be estimated";
            case SqlStatementAnalyzer.NOT_SINGLE_SELECT ->
                    "Only a single SELECT statement can be estimated";
            case SqlStatementAnalyzer.EMPTY_SQL ->
                    "SQL statement is empty";
            case SqlStatementAnalyzer.NESTED_WRITE ->
                    "Statements with nested writes cannot be estimated";
            case SqlStatementAnalyzer.LOCKING_READ ->
                    "Locking reads cannot be estimated";
            case SqlStatementAnalyzer.SELECT_INTO ->
                    "SELECT INTO cannot be estimated";
            case SqlStatementAnalyzer.ASSIGNMENT ->
                    "Statements with assignment cannot be estimated";
            case SqlStatementAnalyzer.EXPLAIN_WRAPPER ->
                    "Wrapped EXPLAIN statements cannot be estimated";
            case SqlStatementAnalyzer.UNBOUND_PARAMETER ->
                    "Statements with unbound parameters cannot be estimated";
            case SqlStatementAnalyzer.DYNAMIC_EXECUTION ->
                    "Dynamic SQL cannot be estimated";
            case PlanCodes.PLAN_LIMIT_REACHED ->
                    "SQL exceeds the execution plan size limit";
            default -> "This statement cannot be estimated";
        };
    }

    private static boolean isCancelled(SQLException e) {
        return "57014".equals(e.getSQLState())
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel"));
    }

    private static ExecutionPlan failure(
            String requestId,
            PlanDatabase database,
            String sql,
            ExecutionPlanCapabilities capabilities,
            PlanStatus status,
            String code,
            String message
    ) {
        return failure(requestId, database, sql, capabilities, status, code, message, null);
    }

    private static ExecutionPlan failure(
            String requestId,
            PlanDatabase database,
            String sql,
            ExecutionPlanCapabilities capabilities,
            PlanStatus status,
            String code,
            String message,
            SQLException exception
    ) {
        PlanDiagnostic error = new PlanDiagnostic(
                code,
                message,
                exception == null ? null : exception.getSQLState(),
                exception == null ? null : Integer.toString(exception.getErrorCode())
        );
        return new ExecutionPlan(
                requestId,
                database,
                null,
                PlanMode.ESTIMATED,
                status,
                sql,
                List.of(),
                null,
                null,
                null,
                false,
                capabilities,
                List.of(),
                PlanEffects.unchangedReuse(),
                error,
                List.of()
        );
    }
}
