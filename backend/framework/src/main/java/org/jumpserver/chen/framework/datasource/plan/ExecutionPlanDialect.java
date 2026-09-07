package org.jumpserver.chen.framework.datasource.plan;

import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;

import java.sql.SQLException;

public interface ExecutionPlanDialect {
    PlanDatabase database();

    ExecutionPlanCapabilities capabilities();

    DialectPlanResult explainEstimated(PlanExecutionContext context, SqlStatementAnalysis statement) throws SQLException;
}
