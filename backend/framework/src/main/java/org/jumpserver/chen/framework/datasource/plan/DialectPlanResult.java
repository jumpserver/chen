package org.jumpserver.chen.framework.datasource.plan;

import java.util.List;
import java.util.Objects;

public record DialectPlanResult(
        String serverVersion,
        PlanStatus status,
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
    public DialectPlanResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(effects, "effects");
        roots = roots == null ? List.of() : List.copyOf(roots);
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
