package org.jumpserver.chen.framework.datasource.plan;

import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;

public final class UnsupportedExecutionPlanDialect implements ExecutionPlanDialect {
    public static final UnsupportedExecutionPlanDialect INSTANCE = new UnsupportedExecutionPlanDialect();

    private UnsupportedExecutionPlanDialect() {
    }

    public static UnsupportedExecutionPlanDialect getInstance() {
        return INSTANCE;
    }

    @Override
    public PlanDatabase database() {
        return null;
    }

    @Override
    public ExecutionPlanCapabilities capabilities() {
        return ExecutionPlanCapabilities.unsupported();
    }

    @Override
    public DialectPlanResult explainEstimated(PlanExecutionContext context, SqlStatementAnalysis statement) {
        return new DialectPlanResult(
                context == null ? null : context.serverVersion(),
                PlanStatus.UNSUPPORTED_DATABASE,
                java.util.List.of(),
                null,
                null,
                null,
                false,
                capabilities(),
                java.util.List.of(),
                PlanEffects.unchangedReuse(),
                PlanDiagnostic.of(PlanCodes.UNSUPPORTED_DATABASE, "Execution plan is not implemented for this datasource"),
                java.util.List.of()
        );
    }
}
