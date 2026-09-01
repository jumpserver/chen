package org.jumpserver.chen.web.ai;

import com.alibaba.druid.DbType;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.Session;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlAgentToolServiceTest {
    @Test
    void resolvesContextFromActiveConsoleWhenResourceIndexWasRefreshed() {
        String nodeKey = "datasource:root,schema:public,folder:tables,table:settings_setting";
        var session = mock(Session.class);
        var datasource = mock(Datasource.class);
        var connectionManager = mock(ConnectionManager.class);
        var resourceBrowser = mock(ResourceBrowser.class);
        var console = mock(Console.class);
        var connectInfo = new DBConnectInfo();
        connectInfo.setDbType("postgresql");

        when(session.isActive()).thenReturn(true);
        when(session.getDatasource()).thenReturn(datasource);
        when(session.getConsoles()).thenReturn(Map.of("console-1", console));
        when(datasource.getConnectInfo()).thenReturn(connectInfo);
        when(datasource.getConnectionManager()).thenReturn(connectionManager);
        when(datasource.getDruidDbType()).thenReturn(DbType.postgresql);
        when(datasource.getResourceBrowser()).thenReturn(resourceBrowser);
        when(connectionManager.getContextKey()).thenReturn("schema");
        when(connectionManager.getDatabaseContextKey()).thenReturn("database");
        when(resourceBrowser.getIndexedNode(nodeKey)).thenReturn(null);
        when(console.getNodeKey()).thenReturn(nodeKey);
        when(console.getContext()).thenReturn(new ConsoleContext(
                nodeKey, "table", "jumpserver", "jumpserver.public", "settings_setting"
        ));

        var service = new SqlAgentToolService();
        var resolved = service.resolveRequestContext(session, """
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
        assertEquals("jumpserver", resolved.database());
        assertEquals("public", resolved.schema());
        assertEquals("settings_setting", resolved.table());
        var sanitized = JsonParser.parseString(resolved.sanitizedJson()).getAsJsonObject();
        assertEquals("public", sanitized.get("schema").getAsString());
        assertEquals("jumpserver.public", sanitized.get("displaySchema").getAsString());
        assertEquals("table", sanitized.get("nodeType").getAsString());
        assertEquals("settings_setting", sanitized.get("table").getAsString());
        var connection = sanitized.getAsJsonObject("connectionContext");
        assertEquals("jumpserver.public", connection.get("displaySchema").getAsString());
        assertEquals("public", connection.get("resolvedSchema").getAsString());
        assertEquals("\"", connection.get("identifierQuote").getAsString());
        assertFalse(connection.get("businessRowAccess").getAsBoolean());
        assertEquals("", sanitized.get("documentSql").getAsString());
        assertEquals("SELECT id FROM settings_setting", sanitized.get("selectedSql").getAsString());
        assertEquals("settings_setting",
                sanitized.getAsJsonArray("referencedTables").get(0).getAsString());
        assertTrue(sanitized.has("currentSqlAnalysis"));

        when(console.getContext()).thenReturn(new ConsoleContext(
                nodeKey, "table", "jumpserver", "jumpserver.private", "settings_setting"
        ));
        assertThrows(IllegalStateException.class, () -> service.execute(
                session, resolved, "validate_sql", "{\"sql\":\"SELECT 1\"}"
        ));
    }

    @Test
    void normalizesCatalogQualifiedSchemaForJdbcMetadata() {
        assertEquals("public", SqlAgentToolService.normalizeMetadataSchema(
                "jumpserver", "jumpserver.public"
        ));
        assertEquals("public", SqlAgentToolService.normalizeMetadataSchema(
                "jumpserver", "public"
        ));
        assertEquals("Public", SqlAgentToolService.normalizeMetadataSchema(
                "JumpServer", "jumpserver.Public"
        ));
        assertEquals("", SqlAgentToolService.normalizeMetadataSchema("jumpserver", null));
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
    void preparesValidatedDraftWithoutExecutingSql() throws Exception {
        String nodeKey = "datasource:root,schema:public";
        var session = mock(Session.class);
        var datasource = mock(Datasource.class);
        var connectionManager = mock(ConnectionManager.class);
        var resourceBrowser = mock(ResourceBrowser.class);
        var connectInfo = new DBConnectInfo();
        connectInfo.setDbType("postgresql");

        when(session.isActive()).thenReturn(true);
        when(session.getDatasource()).thenReturn(datasource);
        when(session.getConsoles()).thenReturn(Map.of());
        when(datasource.getConnectInfo()).thenReturn(connectInfo);
        when(datasource.getConnectionManager()).thenReturn(connectionManager);
        when(datasource.getDruidDbType()).thenReturn(DbType.postgresql);
        when(datasource.getResourceBrowser()).thenReturn(resourceBrowser);
        when(resourceBrowser.getIndexedNode(nodeKey)).thenReturn(
                new org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot(
                        nodeKey, "schema", "jumpserver", "public", "", null
                )
        );

        var service = new SqlAgentToolService();
        var context = service.resolveRequestContext(session, """
                {
                  "nodeKey":"%s",
                  "paneId":"pane-1",
                  "workspaceTabKind":"database",
                  "revision":0,
                  "selectionFrom":0,
                  "selectionTo":0
                }
                """.formatted(nodeKey), "generate");
        var result = JsonParser.parseString(service.execute(
                session,
                context,
                "propose_sql",
                "{\"sql\":\"SELECT id FROM users\",\"explanation\":\"List users\"}"
        )).getAsJsonObject();

        assertEquals("proposal", result.get("kind").getAsString());
        assertTrue(result.getAsJsonObject("analysis").get("valid").getAsBoolean());
        assertEquals("new_query", result.getAsJsonObject("proposal")
                .getAsJsonObject("base").get("target").getAsString());
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

    @Test
    void metadataApprovalStaysInsideActiveSchema() {
        var service = new SqlAgentToolService();
        var context = agentContext("jumpserver", "public", "node-public");

        var scope = service.resolveMetadataApprovalScope(context, """
                {"schema":"public","tables":["public.users","\\\"public\\\".\\\"roles\\\""]}
                """);

        assertEquals("jumpserver", scope.database());
        assertEquals("public", scope.schema());
        assertEquals(List.of("users", "roles"), scope.tables());
        assertThrows(IllegalArgumentException.class, () -> service.resolveMetadataApprovalScope(context, """
                {"schema":"information_schema","tables":["tables"]}
                """));
        assertThrows(IllegalArgumentException.class, () -> service.resolveMetadataApprovalScope(context, """
                {"tables":["private.users"]}
                """));
        assertThrows(IllegalArgumentException.class, () -> service.resolveMetadataApprovalScope(
                agentContext("jumpserver", "information_schema", "node-system"),
                "{\"tables\":[\"tables\"]}"
        ));
    }

    @Test
    void sessionMetadataApprovalOnlyCoversSameOrSmallerScope() {
        var service = new SqlAgentToolService();
        var context = agentContext("jumpserver", "public", "node-public");
        var grant = service.resolveMetadataApprovalScope(context, """
                {"tables":["users","roles"]}
                """);

        assertTrue(grant.covers(service.resolveMetadataApprovalScope(context, """
                {"tables":["users"]}
                """)));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(context, """
                {"tables":["users","accounts"]}
                """)));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(
                agentContext("jumpserver", "public", "another-node"),
                "{\"tables\":[\"users\"]}"
        )));
    }

    @Test
    void metadataSearchApprovalRequiresTheSameSearch() {
        var service = new SqlAgentToolService();
        var context = agentContext("jumpserver", "public", "node-public");
        var grant = service.resolveMetadataApprovalScope(context, "{\"query\":\"user\"}");

        assertTrue(grant.covers(service.resolveMetadataApprovalScope(context, "{\"query\":\"USER\"}")));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(context, "{\"query\":\"account\"}")));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(context, "{\"tables\":[\"users\"]}")));
    }

    @Test
    void tableDiscoveryApprovalCoversOnlyBoundedFollowUpInspectionInTheSameContext() {
        var service = new SqlAgentToolService();
        var context = agentContext("jumpserver", "public", "node-public");
        var grant = service.resolveMetadataApprovalScope(context, "{\"query\":\"*\"}");

        assertTrue(grant.discovery());
        assertTrue(grant.covers(service.resolveMetadataApprovalScope(context, """
                {"tables":["users","orders"]}
                """)));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(context, "{\"query\":\"user\"}")));
        assertFalse(grant.covers(service.resolveMetadataApprovalScope(
                agentContext("jumpserver", "private", "node-private"),
                "{\"tables\":[\"users\"]}"
        )));
        assertThrows(IllegalArgumentException.class, () -> service.resolveMetadataApprovalScope(
                context, "{\"query\":\"*\",\"tables\":[\"users\"]}"
        ));
    }

    @Test
    void rejectsSystemCatalogReferencesWithoutBlockingValidTableAliases() {
        String nodeKey = "datasource:root,schema:public";
        var session = mock(Session.class);
        var datasource = mock(Datasource.class);
        var connectionManager = mock(ConnectionManager.class);
        var resourceBrowser = mock(ResourceBrowser.class);
        var console = mock(Console.class);
        var connectInfo = new DBConnectInfo();
        connectInfo.setDbType("postgresql");

        when(session.isActive()).thenReturn(true);
        when(session.getDatasource()).thenReturn(datasource);
        when(session.getConsoles()).thenReturn(Map.of("console-1", console));
        when(datasource.getConnectInfo()).thenReturn(connectInfo);
        when(datasource.getConnectionManager()).thenReturn(connectionManager);
        when(datasource.getDruidDbType()).thenReturn(DbType.postgresql);
        when(datasource.getResourceBrowser()).thenReturn(resourceBrowser);
        when(connectionManager.getContextKey()).thenReturn("schema");
        when(connectionManager.getDatabaseContextKey()).thenReturn("database");
        when(console.getNodeKey()).thenReturn(nodeKey);
        when(console.getContext()).thenReturn(new ConsoleContext(
                nodeKey, "schema", "jumpserver", "public", ""
        ));

        var service = new SqlAgentToolService();
        var aliasContext = service.resolveRequestContext(
                session,
                """
                        {
                          "nodeKey":"%s",
                          "consoleId":"console-1",
                          "workspaceTabKind":"query",
                          "documentSql":"SELECT sys.id FROM users AS sys",
                          "selectionFrom":0,
                          "selectionTo":0
                        }
                        """.formatted(nodeKey),
                "explain"
        );
        var aliasAnalysis = JsonParser.parseString(aliasContext.sanitizedJson())
                .getAsJsonObject().getAsJsonObject("currentSqlAnalysis");
        assertEquals("users", aliasAnalysis.getAsJsonArray("tables").get(0).getAsString());

        assertThrows(IllegalArgumentException.class, () -> service.resolveRequestContext(
                session,
                """
                        {
                          "nodeKey":"%s",
                          "consoleId":"console-1",
                          "workspaceTabKind":"query",
                          "documentSql":"SELECT * FROM information_schema.tables",
                          "selectionFrom":0,
                          "selectionTo":0
                        }
                        """.formatted(nodeKey),
                "explain"
        ));
        assertThrows(IllegalArgumentException.class, () -> service.resolveRequestContext(
                session,
                """
                        {
                          "nodeKey":"%s",
                          "consoleId":"console-1",
                          "workspaceTabKind":"query",
                          "documentSql":"SELECT * FRM information_schema.tables",
                          "selectionFrom":0,
                          "selectionTo":0
                        }
                        """.formatted(nodeKey),
                "repair"
        ));
    }

    private static SqlAgentToolService.AgentRequestContext agentContext(
            String database,
            String schema,
            String nodeKey
    ) {
        return new SqlAgentToolService.AgentRequestContext(
                "postgresql", database, schema, "", nodeKey, "", "", "{}"
        );
    }
}
