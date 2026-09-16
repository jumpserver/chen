package org.jumpserver.chen.framework.utils;

import com.alibaba.druid.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageUtilsSqlServerLimitTest {
    private static final String CROSS_JOIN_SQL = """
            SELECT
                a.object_id AS a_id,
                b.object_id AS b_id
            FROM sys.all_objects a
            CROSS JOIN sys.all_objects b
            """;

    @Test
    void firstPageUsesTop() {
        String sql = normalize(PageUtils.limit(CROSS_JOIN_SQL, DbType.sqlserver, 0, 100));

        assertTrue(sql.startsWith("SELECT TOP 100 "));
        assertTrue(sql.contains("FROM sys.all_objects a CROSS JOIN sys.all_objects b"));
        assertFalse(sql.contains("ROW_NUMBER()"));
        assertFalse(sql.contains("ORDER BY SELECT NULL"));
    }

    @Test
    void defaultPageSizeWrapsFirstPageWithTop50() {
        String sql = normalize(PageUtils.limit(
                "SELECT id, name, CreditLimit FROM [dbo].[Customers] ORDER BY [CreditLimit] DESC",
                DbType.sqlserver, 0, 50));

        assertTrue(sql.startsWith("SELECT TOP 50 "));
        assertTrue(sql.contains("ORDER BY CreditLimit DESC") || sql.contains("ORDER BY [CreditLimit] DESC"));
        assertFalse(sql.contains("ROW_NUMBER()"));
    }

    @Test
    void defaultPageSizeSecondPageUsesRowNumber() {
        String sql = normalize(PageUtils.limit(
                "SELECT id, name, CreditLimit FROM [dbo].[Customers] ORDER BY [CreditLimit] DESC",
                DbType.sqlserver, 50, 50));

        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY [CreditLimit] DESC) AS ROWNUM")
                || sql.contains("ROW_NUMBER() OVER (ORDER BY CreditLimit DESC) AS ROWNUM"));
        assertTrue(sql.contains("WHERE ROWNUM > 50 AND ROWNUM <= 100"));
        assertFalse(sql.contains("ORDER BY SELECT NULL"));
    }

    @Test
    void secondPageWrapsWithParenthesizedDummyOrderBy() {
        String sql = normalize(PageUtils.limit(CROSS_JOIN_SQL, DbType.sqlserver, 100, 100));

        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS ROWNUM"));
        assertFalse(sql.contains("ORDER BY SELECT NULL"));
        assertTrue(sql.contains("WHERE ROWNUM > 100 AND ROWNUM <= 200"));
        assertTrue(sql.contains("FROM (SELECT"));
    }

    @Test
    void secondPageReusesOriginalOrderBy() {
        String source = "SELECT a.object_id AS a_id FROM sys.all_objects a ORDER BY a.object_id";
        String sql = normalize(PageUtils.limit(source, DbType.sqlserver, 100, 100));

        assertTrue(sql.contains("ROW_NUMBER() OVER (ORDER BY a.object_id) AS ROWNUM"));
        assertEquals(1, countOf(sql, "ORDER BY a.object_id"));
        assertFalse(sql.contains("SELECT NULL"));
        assertTrue(sql.contains("WHERE ROWNUM > 100 AND ROWNUM <= 200"));
    }

    @Test
    void firstPageKeepsOriginalOrderByWithTop() {
        String source = "SELECT a.object_id AS a_id FROM sys.all_objects a ORDER BY a.object_id";
        String sql = normalize(PageUtils.limit(source, DbType.sqlserver, 0, 100));

        assertTrue(sql.startsWith("SELECT TOP 100 "));
        assertTrue(sql.endsWith("ORDER BY a.object_id"));
        assertFalse(sql.contains("ROW_NUMBER()"));
    }

    @Test
    void otherDialectsKeepExistingLimitStyle() {
        String mysql = normalize(PageUtils.limit("SELECT id FROM t", DbType.mysql, 100, 50));
        assertTrue(mysql.contains("LIMIT 100, 50") || mysql.contains("LIMIT 50 OFFSET 100"));

        String postgres = normalize(PageUtils.limit("SELECT id FROM t", DbType.postgresql, 100, 50));
        assertTrue(postgres.contains("LIMIT 50 OFFSET 100") || postgres.contains("LIMIT 50"));

        String oracle = normalize(PageUtils.limit("SELECT id FROM t", DbType.oracle, 100, 50));
        assertTrue(oracle.contains("ROWNUM"));
        assertTrue(oracle.contains("RN > 100") || oracle.contains("ROWNUM <= 150"));

        String db2 = normalize(PageUtils.limit("SELECT id FROM t", DbType.db2, 0, 50)).toUpperCase();
        assertTrue(db2.contains("FETCH FIRST") || db2.contains("FIRST 50") || db2.contains("FIRST"));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .trim();
    }

    private static int countOf(String sql, String token) {
        int count = 0;
        for (int index = 0; (index = sql.indexOf(token, index)) >= 0; index += token.length()) {
            count++;
        }
        return count;
    }
}
