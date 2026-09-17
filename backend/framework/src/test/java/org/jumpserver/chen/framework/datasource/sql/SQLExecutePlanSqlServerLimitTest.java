package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLExecutePlanSqlServerLimitTest {
    private static final String TOP5_SQL =
            "SELECT TOP (5) id, name, CreditLimit FROM [dbo].[Customers] ORDER BY [CreditLimit] DESC";
    private static final String ALL_ROWS_SQL =
            "SELECT id, name, CreditLimit FROM [dbo].[Customers] ORDER BY [CreditLimit] DESC";

    @Test
    void generateTargetSQLKeepsUserTopInsteadOfDefaultPageSize() throws Exception {
        SQLExecutePlan plan = plan(TOP5_SQL, 0, 50);
        plan.generateTargetSQL();

        String target = compact(plan.getTargetSQL());
        assertEquals(5, PageUtils.getLimit(plan.getTargetSQL(), DbType.sqlserver));
        assertTrue(target.contains("TOP (5)") || target.contains("TOP 5"));
        assertFalse(target.contains("TOP 50"));
    }

    @Test
    void generateTargetSQLStillWrapsDefaultPageSizeWhenNoTop() throws Exception {
        SQLExecutePlan plan = plan(ALL_ROWS_SQL, 0, 50);
        plan.setSqlActuator(countStub(7));
        plan.generateTargetSQL();

        String target = compact(plan.getTargetSQL());
        assertTrue(target.startsWith("SELECT TOP 50 "));
        assertFalse(target.contains("ROW_NUMBER()"));
    }

    private static SQLExecutePlan plan(String sql, int offset, int limit) {
        SQLExecutePlan plan = new SQLExecutePlan(sql, DbType.sqlserver);
        SQLQueryParams params = new SQLQueryParams();
        params.setOffset(offset);
        params.setLimit(limit);
        plan.setSqlQueryParams(params);
        return plan;
    }

    private static SQLActuator countStub(int count) {
        return (SQLActuator) java.lang.reflect.Proxy.newProxyInstance(
                SQLActuator.class.getClassLoader(),
                new Class[]{SQLActuator.class},
                (proxy, method, args) -> {
                    if ("count".equals(method.getName())) {
                        return count;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        return null;
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
