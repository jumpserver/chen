package org.jumpserver.chen.web.ai;

import com.alibaba.druid.DbType;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.Session;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlAgentToolServiceTest {
    @Test
    void resolvesContextFromActiveConsoleWhenResourceIndexWasRefreshed() {
        String nodeKey = "datasource:root,schema:public,folder:tables,table:settings_setting";
        var session = mock(Session.class);
        var datasource = mock(Datasource.class);
        var resourceBrowser = mock(ResourceBrowser.class);
        var console = mock(Console.class);
        var connectInfo = new DBConnectInfo();
        connectInfo.setDbType("postgresql");

        when(session.isActive()).thenReturn(true);
        when(session.getDatasource()).thenReturn(datasource);
        when(session.getConsoles()).thenReturn(Map.of("console-1", console));
        when(datasource.getConnectInfo()).thenReturn(connectInfo);
        when(datasource.getDruidDbType()).thenReturn(DbType.postgresql);
        when(datasource.getResourceBrowser()).thenReturn(resourceBrowser);
        when(resourceBrowser.getIndexedNode(nodeKey)).thenReturn(null);
        when(console.getNodeKey()).thenReturn(nodeKey);
        when(console.getContext()).thenReturn(new ConsoleContext(
                nodeKey, "table", null, "public", "settings_setting"
        ));

        var resolved = new SqlAgentToolService().resolveRequestContext(session, """
                {
                  "nodeKey": "%s",
                  "consoleId": "console-1",
                  "workspaceTabId": "query-1",
                  "workspaceTabKind": "query",
                  "documentSql": "SELECT 1;\\nSELECT id FROM settings_setting",
                  "selectionFrom": 10,
                  "selectionTo": 41
                }
                """.formatted(nodeKey), "repair");

        assertEquals(nodeKey, resolved.nodeKey());
        assertEquals("public", resolved.schema());
        assertEquals("settings_setting", resolved.table());
        var sanitized = JsonParser.parseString(resolved.sanitizedJson()).getAsJsonObject();
        assertEquals("", sanitized.get("documentSql").getAsString());
        assertEquals("SELECT id FROM settings_setting", sanitized.get("selectedSql").getAsString());
        assertEquals("settings_setting",
                sanitized.getAsJsonArray("referencedTables").get(0).getAsString());
        assertTrue(sanitized.has("currentSqlAnalysis"));
    }

    @Test
    void validatesReadOnlySqlForEveryChenDialect() {
        List<DbType> dialects = List.of(
                DbType.mysql,
                DbType.mariadb,
                DbType.postgresql,
                DbType.sqlserver,
                DbType.oracle,
                DbType.clickhouse,
                DbType.dm,
                DbType.db2
        );

        for (DbType dialect : dialects) {
            Map<String, Object> result = SqlAgentToolService.validateSQL(dialect, "SELECT id FROM users");
            assertEquals(true, result.get("valid"), dialect.name());
            assertEquals(1, result.get("statementCount"), dialect.name());
            assertEquals(1, result.get("riskLevel"), dialect.name());
        }
    }

    @Test
    void reportsSyntaxErrorsWithoutExecutingSql() {
        Map<String, Object> result = SqlAgentToolService.validateSQL(DbType.postgresql, "SELECT * FRM users");

        assertEquals(false, result.get("valid"));
        assertEquals(0, result.get("statementCount"));
        assertFalse(((List<?>) result.get("errors")).isEmpty());
    }

    @Test
    void identifiesMultiStatementWriteRisk() {
        Map<String, Object> result = SqlAgentToolService.validateSQL(
                DbType.mysql,
                "SELECT id FROM users; DELETE FROM users WHERE id = 1"
        );

        assertEquals(true, result.get("valid"));
        assertEquals(2, result.get("statementCount"));
        assertEquals("MULTI", result.get("statementType"));
        assertTrue(((Number) result.get("riskLevel")).intValue() >= 3);
    }

    @Test
    void errorContextDropsUnknownBusinessData() {
        var sanitized = SqlAgentToolService.sanitizeLastError(JsonParser.parseString("""
                {
                  "kind": "execute",
                  "message": "column does not exist",
                  "sql": "SELECT secret FROM users",
                  "rows": [{"secret": "must-not-leave-chen"}]
                }
                """));

        assertEquals("execute", sanitized.get("kind").getAsString());
        assertEquals("column does not exist", sanitized.get("message").getAsString());
        assertFalse(sanitized.has("rows"));
    }
}
