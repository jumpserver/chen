package org.jumpserver.chen.framework.utils;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageUtilsFilterTest {
    @Test
    void addsFilterToTableQuery() {
        String sql = PageUtils.filter(
                "select * from `demo`.`users`",
                DbType.mysql,
                "status = 'active' AND id > 10"
        );

        String where = where(sql, DbType.mysql);
        assertTrue(where.contains("status = 'active'"));
        assertTrue(where.contains("id > 10"));
    }

    @Test
    void combinesWithExistingWhereClause() {
        String sql = PageUtils.filter("select * from users where tenant_id = 7", DbType.mysql, "enabled = 1");

        String where = where(sql, DbType.mysql);
        assertTrue(where.contains("tenant_id = 7"));
        assertTrue(where.contains("enabled = 1"));
    }

    @Test
    void acceptsDialectSpecificQuotedIdentifiers() {
        String sql = PageUtils.filter("select * from \"public\".\"users\"", DbType.postgresql, "\"displayName\" IS NOT NULL");

        assertTrue(where(sql, DbType.postgresql).contains("\"displayName\" IS NOT NULL"));
    }

    @Test
    void emptyFilterLeavesSqlUnchanged() {
        String sql = "select * from users";
        assertEquals(sql, PageUtils.filter(sql, DbType.mysql, "  "));
    }

    @Test
    void rejectsAdditionalStatementsAndClauses() {
        assertThrows(IllegalArgumentException.class,
                () -> PageUtils.filter("select * from users", DbType.mysql, "1 = 1; delete from users"));
        assertThrows(IllegalArgumentException.class,
                () -> PageUtils.filter("select * from users", DbType.mysql, "1 = 1 order by id"));
        assertThrows(IllegalArgumentException.class,
                () -> PageUtils.filter("select * from users", DbType.mysql, "1 = 1 limit 1"));
    }

    private String where(String sql, DbType dbType) {
        SQLSelectStatement statement = (SQLSelectStatement) SQLUtils.parseSingleStatement(sql, dbType);
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) statement.getSelect().getQuery();
        return query.getWhere().toString();
    }
}
