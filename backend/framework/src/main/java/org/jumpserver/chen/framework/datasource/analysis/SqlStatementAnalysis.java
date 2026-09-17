package org.jumpserver.chen.framework.datasource.analysis;

import com.alibaba.druid.sql.ast.SQLStatement;

import java.util.List;
import java.util.Objects;

public record SqlStatementAnalysis(
        String sql,
        StatementKind kind,
        SQLStatement ast,
        boolean singleStatement,
        boolean nestedWrite,
        boolean lockingRead,
        boolean selectInto,
        boolean assignment,
        boolean explainWrapper,
        boolean unboundParameter,
        boolean dynamicExecution,
        List<String> functionNames,
        List<String> rejectionCodes
) {
    public SqlStatementAnalysis {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(kind, "kind");
        functionNames = functionNames == null ? List.of() : List.copyOf(functionNames);
        rejectionCodes = rejectionCodes == null ? List.of() : List.copyOf(rejectionCodes);
    }

    public boolean estimable() {
        return rejectionCodes.isEmpty()
                && singleStatement
                && kind == StatementKind.SELECT
                && !nestedWrite
                && !lockingRead
                && !selectInto
                && !assignment
                && !explainWrapper
                && !unboundParameter
                && !dynamicExecution;
    }
}
