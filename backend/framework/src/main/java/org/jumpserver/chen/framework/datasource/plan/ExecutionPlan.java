package org.jumpserver.chen.framework.datasource.plan;

import java.util.List;
import java.util.Objects;

public record ExecutionPlan(
        String requestId,
        PlanDatabase database,
        String serverVersion,
        PlanMode mode,
        PlanStatus status,
        String sql,
        List<PlanNode> roots,
        String rawText,
        PlanRawFormat rawFormat,
        String rawFormatVersion,
        boolean rawTruncated,
        ExecutionPlanCapabilities capabilities,
        List<PlanPrerequisite> prerequisites,
        PlanEffects effects,
        PlanDiagnostic error,
        List<PlanDiagnostic> warnings
) {
    public ExecutionPlan {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(effects, "effects");
        roots = roots == null ? List.of() : List.copyOf(roots);
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ExecutionPlan fromDialect(
            String requestId,
            PlanDatabase database,
            PlanMode mode,
            String sql,
            DialectPlanResult result
    ) {
        return new ExecutionPlan(
                requestId,
                database,
                result.serverVersion(),
                mode,
                result.status(),
                sql,
                result.roots(),
                result.rawText(),
                result.rawFormat(),
                result.rawFormatVersion(),
                result.rawTruncated(),
                result.capabilities(),
                result.prerequisites(),
                result.effects(),
                result.error(),
                result.warnings()
        );
    }
}
