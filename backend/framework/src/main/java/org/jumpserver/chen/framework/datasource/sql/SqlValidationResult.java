package org.jumpserver.chen.framework.datasource.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SqlValidationResult(
        boolean parseable,
        int statementCount,
        String statementType,
        int riskLevel,
        String riskReason,
        List<String> tables,
        List<String> columns,
        List<String> errors,
        List<String> warnings
) {
    public SqlValidationResult {
        tables = tables == null ? List.of() : List.copyOf(tables);
        columns = columns == null ? List.of() : List.copyOf(columns);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        statementType = statementType == null ? "UNKNOWN" : statementType;
        riskReason = riskReason == null ? "" : riskReason;
    }

    public SqlValidationResult withRiskReason(String reason) {
        List<String> nextWarnings = parseable || reason == null || reason.isBlank()
                ? warnings
                : List.of(reason);
        return new SqlValidationResult(
                parseable,
                statementCount,
                statementType,
                riskLevel,
                reason,
                tables,
                columns,
                errors,
                nextWarnings
        );
    }

    public Map<String, Object> toAnalysisMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", parseable);
        result.put("parseable", parseable);
        result.put("statementCount", statementCount);
        result.put("statementType", statementType);
        result.put("riskLevel", riskLevel);
        result.put("riskReason", riskReason);
        result.put("tables", tables);
        result.put("columns", columns);
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }
}
