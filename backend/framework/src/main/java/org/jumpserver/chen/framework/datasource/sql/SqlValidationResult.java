package org.jumpserver.chen.framework.datasource.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SqlValidationResult(
        boolean parseable,
        int statementCount,
        String statementType,
        List<String> tables,
        List<String> columns,
        List<String> errors
) {
    public SqlValidationResult {
        tables = tables == null ? List.of() : List.copyOf(tables);
        columns = columns == null ? List.of() : List.copyOf(columns);
        errors = errors == null ? List.of() : List.copyOf(errors);
        statementType = statementType == null ? "UNKNOWN" : statementType;
    }

    public Map<String, Object> toAnalysisMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", parseable);
        result.put("parseable", parseable);
        result.put("statementCount", statementCount);
        result.put("statementType", statementType);
        result.put("tables", tables);
        result.put("columns", columns);
        result.put("errors", errors);
        return result;
    }
}
