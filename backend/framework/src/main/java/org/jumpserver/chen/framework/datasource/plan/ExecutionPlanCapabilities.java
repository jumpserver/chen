package org.jumpserver.chen.framework.datasource.plan;

import java.util.Objects;

public record ExecutionPlanCapabilities(
        boolean supported,
        boolean structured,
        boolean raw,
        boolean cost,
        boolean estimatedRows,
        boolean predicates,
        boolean twoStep,
        boolean requiresSessionState,
        boolean requiresPlanTable,
        PlanTransactionPolicy transactionPolicy
) {
    public ExecutionPlanCapabilities {
        Objects.requireNonNull(transactionPolicy, "transactionPolicy");
    }

    public static ExecutionPlanCapabilities unsupported() {
        return new ExecutionPlanCapabilities(
                false, false, false, false, false, false, false, false, false,
                PlanTransactionPolicy.STATEMENT_ONLY
        );
    }

    public static ExecutionPlanCapabilities postgresql() {
        return new ExecutionPlanCapabilities(
                true, true, true, true, true, true, false, false, false,
                PlanTransactionPolicy.SAVEPOINT_IF_ACTIVE
        );
    }

    public static ExecutionPlanCapabilities oracle() {
        return new ExecutionPlanCapabilities(
                true, true, true, true, true, true, true, false, true,
                PlanTransactionPolicy.AUXILIARY_DML
        );
    }

    public static ExecutionPlanCapabilities mysql() {
        return new ExecutionPlanCapabilities(
                true, true, true, true, true, true, false, false, false,
                PlanTransactionPolicy.STATEMENT_ONLY
        );
    }

    public static ExecutionPlanCapabilities mariadb() {
        return new ExecutionPlanCapabilities(
                true, true, true, false, true, true, false, false, false,
                PlanTransactionPolicy.STATEMENT_ONLY
        );
    }

    public static ExecutionPlanCapabilities sqlserver() {
        return new ExecutionPlanCapabilities(
                true, true, true, true, true, true, true, true, false,
                PlanTransactionPolicy.RESTORE_SESSION
        );
    }
}
