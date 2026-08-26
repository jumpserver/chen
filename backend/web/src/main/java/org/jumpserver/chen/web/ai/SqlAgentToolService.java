package org.jumpserver.chen.web.ai;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.session.Session;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SqlAgentToolService {
    private static final Gson GSON = new Gson();
    private static final int MAX_CONTEXT_BYTES = 256 * 1024;
    private static final int MAX_SQL_BYTES = 128 * 1024;
    private static final int MAX_IDENTIFIER_BYTES = 1024;
    private static final int MAX_OBJECTS = 50;
    private static final int MAX_ANALYSIS_COLUMNS = 512;
    static final int MAX_INSPECT_TABLES = 8;
    static final int MAX_DISCOVER_TABLES = 100;
    static final String DISCOVER_TABLES_QUERY = "*";
    private static final int MAX_INSPECT_COLUMNS = 96;
    private static final int MAX_INSPECT_RELATIONS = 48;
    static final List<String> INSPECT_SCHEMA_DATA_CATEGORIES = List.of(
            "connection_metadata", "tables", "columns", "primary_keys",
            "foreign_keys", "indexes", "comments", "default_values"
    );
    private static final Set<String> CONTEXT_NODE_TYPES =
            Set.of("datasource", "database", "schema", "table", "view");
    private static final Set<String> TOOL_NAMES = Set.of("inspect_schema", "validate_sql");
    private static final Set<String> WORKSPACE_TAB_KINDS =
            Set.of("query", "console", "data-view", "database", "none");

    public AgentRequestContext resolveRequestContext(Session session, String contextJson, String operation) {
        if (session == null || session.isClosing() || !session.isActive()) {
            throw new IllegalArgumentException("The database session is not active");
        }
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("Invalid SQL editor context");
        }
        JsonObject submitted;
        try {
            submitted = JsonParser.parseString(contextJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL editor context", e);
        }

        String nodeKey = boundedString(submitted, "nodeKey", MAX_IDENTIFIER_BYTES, true);
        String consoleId = boundedString(submitted, "consoleId", 256, false);
        String workspaceTabId = boundedString(submitted, "workspaceTabId", 256, false);
        String workspaceTabKind = boundedString(submitted, "workspaceTabKind", 32, false);
        if (workspaceTabKind.isBlank()) {
            workspaceTabKind = "none";
        }
        boolean requiresConsole = !"none".equals(workspaceTabKind) && !"database".equals(workspaceTabKind);
        if (!WORKSPACE_TAB_KINDS.contains(workspaceTabKind)
                || (requiresConsole && consoleId.isBlank())) {
            throw new IllegalArgumentException("Invalid database workspace context");
        }
        var console = consoleId.isBlank() ? null : session.getConsoles().get(consoleId);
        if (consoleId.isBlank() ? requiresConsole
                : console == null || !nodeKey.equals(console.getNodeKey())) {
            throw new IllegalArgumentException("Invalid database console context");
        }

        ResourceNodeSnapshot node;
        if (console == null) {
            node = session.getDatasource().getResourceBrowser().getIndexedNode(nodeKey);
        } else {
            var context = console.getContext();
            node = new ResourceNodeSnapshot(
                    context.nodeKey(), context.nodeType(), context.database(), context.schema(), context.table(), null
            );
        }
        if (node == null || !CONTEXT_NODE_TYPES.contains(node.type())) {
            throw new IllegalArgumentException("Invalid database resource context");
        }

        String database = node.database();
        String schema = node.schema();
        String currentContext = "";
        ConnectionManager manager = session.getDatasource().getConnectionManager();
        if (console instanceof QueryConsole queryConsole) {
            currentContext = queryConsole.getCurrentContext();
            if (StringUtils.isNotBlank(currentContext)) {
                if (StringUtils.equals(manager.getContextKey(), manager.getDatabaseContextKey())) {
                    database = currentContext;
                } else {
                    schema = currentContext;
                }
            }
        }
        String displaySchema = schema;
        schema = normalizeMetadataSchema(database, schema);

        String documentSql = boundedString(submitted, "documentSql", MAX_SQL_BYTES, false);
        int selectionFrom = nonNegativeInt(submitted, "selectionFrom");
        int selectionTo = nonNegativeInt(submitted, "selectionTo");
        if (selectionFrom > documentSql.length() || selectionTo > documentSql.length()
                || selectionTo < selectionFrom) {
            throw new IllegalArgumentException("Invalid SQL editor selection");
        }
        boolean hasSelection = selectionTo > selectionFrom;
        String selectedSql = hasSelection ? documentSql.substring(selectionFrom, selectionTo) : "";
        long revision = submitted.has("revision") ? submitted.get("revision").getAsLong() : 0;
        if (revision < 0) {
            throw new IllegalArgumentException("Invalid SQL editor revision");
        }

        Datasource datasource = session.getDatasource();
        JsonObject lastError = "repair".equals(operation) ? sanitizeLastError(submitted.get("lastError")) : null;
        if (lastError != null && hasSelection) {
            lastError.addProperty("sql", selectedSql);
        }
        String dialect = StringUtils.defaultString(datasource.getConnectInfo().getDbType())
                .toLowerCase(Locale.ROOT);
        String targetSql = hasSelection ? selectedSql : documentSql;
        Map<String, Object> sqlAnalysis = StringUtils.isBlank(targetSql)
                ? null : validateSQL(datasource.getDruidDbType(), targetSql);
        assertAllowedAiScope(dialect, database, schema, targetSql, sqlAnalysis);
        JsonObject sanitized = new JsonObject();
        sanitized.addProperty("dialect", dialect);
        addNullableString(sanitized, "database", database);
        addNullableString(sanitized, "schema", schema);
        addNullableString(sanitized, "displaySchema", displaySchema);
        sanitized.addProperty("nodeKey", node.key());
        sanitized.addProperty("nodeType", node.type());
        addNullableString(sanitized, "table", node.table());
        sanitized.addProperty("consoleId", consoleId);
        sanitized.addProperty("paneId", boundedString(submitted, "paneId", 256, false));
        sanitized.addProperty("tabId", boundedString(submitted, "tabId", 256, false));
        sanitized.addProperty("workspaceTabId", workspaceTabId);
        sanitized.addProperty("workspaceTabKind", workspaceTabKind);
        sanitized.addProperty("currentContext", currentContext);
        sanitized.addProperty("revision", revision);
        sanitized.addProperty("selectionFrom", selectionFrom);
        sanitized.addProperty("selectionTo", selectionTo);
        sanitized.addProperty("selectedSql", selectedSql);
        sanitized.addProperty("documentSql", hasSelection ? "" : documentSql);
        sanitized.add("connectionContext", connectionContext(
                dialect, database, displaySchema, schema, currentContext,
                manager.getContextKey(), manager.getDatabaseContextKey(), node
        ));
        if (sqlAnalysis != null) {
            sanitized.add("currentSqlAnalysis", GSON.toJsonTree(sqlAnalysis));
            sanitized.add("referencedTables", GSON.toJsonTree(sqlAnalysis.get("tables")));
        } else {
            sanitized.add("referencedTables", GSON.toJsonTree(List.of()));
        }
        if (lastError != null) {
            sanitized.add("lastError", lastError);
        }
        String sanitizedJson = GSON.toJson(sanitized);
        if (sanitizedJson.length() > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("SQL editor context is too large");
        }
        return new AgentRequestContext(
                dialect, database, schema, node.table(), node.key(),
                consoleId, currentContext,
                sanitizedJson
        );
    }

    public String execute(Session session, AgentRequestContext context, String toolName, String argumentsJson)
            throws SQLException {
        if (session == null || context == null || session.isClosing() || !session.isActive()) {
            throw new IllegalStateException("The database session is not active");
        }
        assertRequestContextCurrent(session, context);
        toolName = StringUtils.defaultString(toolName).trim().toLowerCase(Locale.ROOT);
        if (!TOOL_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("Unsupported SQL assistant tool");
        }
        if (argumentsJson == null || argumentsJson.length() > 32 * 1024) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments");
        }
        JsonObject arguments = parseToolArguments(argumentsJson);

        Datasource datasource = session.getDatasource();
        ConnectionManager connectionManager = datasource.getConnectionManager();
        connectionManager.setDatabaseContext(StringUtils.defaultString(context.database()));
        Object result = switch (toolName) {
            case "inspect_schema" -> inspectSchema(connectionManager, context, arguments);
            case "validate_sql" -> validateSQL(
                    datasource.getDruidDbType(), boundedString(arguments, "sql", MAX_SQL_BYTES, true)
            );
            default -> throw new IllegalArgumentException("Unsupported SQL assistant tool");
        };
        return GSON.toJson(result);
    }

    MetadataApprovalScope resolveMetadataApprovalScope(
            AgentRequestContext context,
            String argumentsJson
    ) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid SQL editor context");
        }
        return resolveMetadataApprovalScope(context, parseToolArguments(argumentsJson));
    }

    private MetadataApprovalScope resolveMetadataApprovalScope(
            AgentRequestContext context,
            JsonObject arguments
    ) {
        String query = boundedString(arguments, "query", MAX_IDENTIFIER_BYTES, false).trim();
        List<String> requestedTables = boundedStringList(arguments, "tables", MAX_INSPECT_TABLES);
        if (query.isBlank() && requestedTables.isEmpty()) {
            throw new IllegalArgumentException("Schema inspection query or tables are required");
        }
        boolean discovery = DISCOVER_TABLES_QUERY.equals(query);
        if (discovery && !requestedTables.isEmpty()) {
            throw new IllegalArgumentException("Table discovery cannot include explicit tables");
        }

        String activeSchema = normalizeMetadataSchema(context.database(), context.schema());
        if (isBlockedSystemScope(context.dialect(), context.database(), activeSchema)) {
            throw new IllegalArgumentException("System database metadata is not available to the SQL assistant");
        }
        if (requiresSchemaScope(context.dialect()) && activeSchema.isBlank()) {
            throw new IllegalArgumentException("Schema inspection requires an active schema");
        }
        if (requiresDatabaseScope(context.dialect()) && StringUtils.isBlank(context.database())) {
            throw new IllegalArgumentException("Schema inspection requires an active database");
        }
        String requestedSchema = normalizeMetadataSchema(
                context.database(), boundedString(arguments, "schema", MAX_IDENTIFIER_BYTES, false)
        );
        if (StringUtils.isNotBlank(requestedSchema)
                && !requestedSchema.equalsIgnoreCase(activeSchema)) {
            throw new IllegalArgumentException("Schema inspection must stay in the active schema");
        }

        List<String> tables = new ArrayList<>(requestedTables.size());
        for (String requestedTable : requestedTables) {
            List<String> parts = qualifiedIdentifierParts(requestedTable);
            validateTableQualifier(parts, context.database(), activeSchema);
            tables.add(parts.get(parts.size() - 1));
        }
        return new MetadataApprovalScope(
                StringUtils.defaultString(context.database()),
                StringUtils.defaultString(activeSchema),
                context.nodeKey(),
                List.copyOf(tables),
                query,
                discovery
        );
    }

    private static JsonObject parseToolArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.length() > 32 * 1024) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments");
        }
        try {
            return JsonParser.parseString(argumentsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments", e);
        }
    }

    static Map<String, Object> validateSQL(DbType dbType, String sql) {
        Map<String, Object> result = new LinkedHashMap<>();
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(sql, dbType);
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("SQL statement is empty");
            }
        } catch (RuntimeException e) {
            result.put("valid", false);
            result.put("statementCount", 0);
            result.put("statementType", "UNKNOWN");
            result.put("riskLevel", 0);
            result.put("riskReason", "SQL syntax validation failed");
            result.put("tables", List.of());
            result.put("columns", List.of());
            result.put("errors", List.of(safeError(e)));
            return result;
        }

        int riskLevel = 0;
        String statementType = statements.size() == 1 ? statementType(statements.get(0)) : "MULTI";
        for (SQLStatement statement : statements) {
            riskLevel = Math.max(riskLevel, riskLevel(statementType(statement)));
            try {
                SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(dbType);
                statement.accept(visitor);
                for (TableStat.Name table : visitor.getTables().keySet()) {
                    addBounded(tables, table.toString(), MAX_OBJECTS);
                }
                Collection<TableStat.Column> statementColumns = visitor.getColumns();
                for (TableStat.Column column : statementColumns) {
                    addBounded(columns, column.toString(), MAX_ANALYSIS_COLUMNS);
                }
            } catch (RuntimeException ignored) {
                // Some vendor-specific statements are syntactically valid but do not support schema statistics.
            }
        }
        result.put("valid", true);
        result.put("statementCount", statements.size());
        result.put("statementType", statementType);
        result.put("riskLevel", riskLevel);
        result.put("riskReason", riskReason(riskLevel, statements.size()));
        result.put("tables", List.copyOf(tables));
        result.put("columns", List.copyOf(columns));
        result.put("errors", errors);
        return result;
    }

    private Map<String, Object> inspectSchema(
            ConnectionManager manager, AgentRequestContext context, JsonObject arguments
    ) throws SQLException {
        MetadataApprovalScope approvedScope = resolveMetadataApprovalScope(context, arguments);
        String query = approvedScope.query();
        List<String> requestedTables = approvedScope.tables();
        int maximumTables = approvedScope.discovery() ? MAX_DISCOVER_TABLES : MAX_INSPECT_TABLES;
        try (Connection connection = manager.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            MetadataScope scope = metadataScope(connection, context, approvedScope.schema());
            Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
            List<String> missingTables = new ArrayList<>();
            for (String requestedTable : requestedTables) {
                String table = unqualifiedTableName(requestedTable);
                Map<String, Object> item = findTable(metadata, scope, context, table);
                if (item == null) {
                    missingTables.add(requestedTable);
                } else {
                    candidates.putIfAbsent(metadataKey(item), item);
                }
            }
            if (!query.isBlank() && candidates.size() < maximumTables) {
                String pattern = approvedScope.discovery()
                        ? "%"
                        : "%" + escapeMetadataPattern(query, metadata.getSearchStringEscape()) + "%";
                try (ResultSet rows = metadata.getTables(
                        scope.catalog(), scope.schema(), pattern,
                        new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"}
                )) {
                    while (rows.next() && candidates.size() < maximumTables) {
                        Map<String, Object> item = tableInfo(rows);
                        if (isMetadataItemWithinScope(item, scope, context.dialect())) {
                            candidates.putIfAbsent(metadataKey(item), item);
                        }
                    }
                }
            }
            Set<String> allowedTables = candidates.values().stream()
                    .map(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<Map<String, Object>> objects = new ArrayList<>(candidates.size());
            for (Map<String, Object> tableInfo : candidates.values()) {
                if (approvedScope.discovery()) {
                    objects.add(tableInfo);
                } else {
                    objects.add(describeTable(
                            metadata, scope, tableInfo, allowedTables, context.dialect()
                    ));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connection", jdbcConnectionContext(metadata, context));
            result.put("requestedScope", metadataScopeMap(
                    context.database(),
                    approvedScope.schema()
            ));
            result.put("resolvedScope", metadataScopeMap(scope.catalog(), scope.schema()));
            result.put("objects", objects);
            result.put("missingTables", missingTables);
            result.put("matchCount", objects.size());
            result.put("discovery", approvedScope.discovery());
            result.put("truncated", candidates.size() == maximumTables);
            return result;
        }
    }

    private Map<String, Object> describeTable(
            DatabaseMetaData metadata,
            MetadataScope scope,
            Map<String, Object> tableInfo,
            Set<String> allowedTables,
            String dialect
    ) throws SQLException {
        String table = String.valueOf(tableInfo.get("name"));
        List<Map<String, Object>> columns = readColumns(metadata, scope, table, MAX_INSPECT_COLUMNS);
        Set<String> primaryKeys = readPrimaryKeys(metadata, scope, table, MAX_INSPECT_COLUMNS);
        for (Map<String, Object> column : columns) {
            column.put("primaryKey", primaryKeys.contains(column.get("name")));
        }
        Map<String, Object> result = new LinkedHashMap<>(tableInfo);
        result.put("columns", columns);
        result.put("foreignKeys", readForeignKeys(
                metadata, scope, table, MAX_INSPECT_RELATIONS, allowedTables, dialect
        ));
        result.put("indexes", readIndexes(metadata, scope, table, MAX_INSPECT_RELATIONS));
        result.put("truncated", columns.size() == MAX_INSPECT_COLUMNS);
        return result;
    }

    private Map<String, Object> findTable(
            DatabaseMetaData metadata,
            MetadataScope scope,
            AgentRequestContext context,
            String table
    )
            throws SQLException {
        String pattern = escapeMetadataPattern(table, metadata.getSearchStringEscape());
        try (ResultSet rows = metadata.getTables(
                scope.catalog(), scope.schema(), pattern,
                new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"}
        )) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    Map<String, Object> item = tableInfo(rows);
                    if (isMetadataItemWithinScope(item, scope, context.dialect())) {
                        return item;
                    }
                }
            }
            return null;
        }
    }

    private static Map<String, Object> tableInfo(ResultSet rows) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", rows.getString("TABLE_CAT"));
        result.put("schema", rows.getString("TABLE_SCHEM"));
        result.put("name", rows.getString("TABLE_NAME"));
        result.put("type", rows.getString("TABLE_TYPE"));
        result.put("comment", rows.getString("REMARKS"));
        return result;
    }

    private static boolean isMetadataItemWithinScope(
            Map<String, Object> item,
            MetadataScope scope,
            String dialect
    ) {
        String database = StringUtils.defaultString((String) item.get("database"));
        String schema = StringUtils.defaultString((String) item.get("schema"));
        if (StringUtils.isNotBlank(scope.catalog())
                && !scope.catalog().equalsIgnoreCase(database)) {
            return false;
        }
        if (StringUtils.isNotBlank(scope.schema())
                && !scope.schema().equalsIgnoreCase(schema)) {
            return false;
        }
        return !isBlockedSystemScope(dialect, database, schema);
    }

    private static String metadataKey(Map<String, Object> item) {
        return (StringUtils.defaultString((String) item.get("database")) + "\u0000"
                + StringUtils.defaultString((String) item.get("schema")) + "\u0000"
                + StringUtils.defaultString((String) item.get("name"))).toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> readColumns(
            DatabaseMetaData metadata, MetadataScope scope, String table, int maximum
    )
            throws SQLException {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(scope.catalog(), scope.schema(), table, "%")) {
            while (rows.next() && columns.size() < maximum) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", rows.getString("COLUMN_NAME"));
                column.put("type", rows.getString("TYPE_NAME"));
                column.put("jdbcType", rows.getInt("DATA_TYPE"));
                column.put("size", rows.getInt("COLUMN_SIZE"));
                column.put("scale", rows.getInt("DECIMAL_DIGITS"));
                column.put("nullable", rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                column.put("defaultValue", rows.getString("COLUMN_DEF"));
                column.put("comment", rows.getString("REMARKS"));
                column.put("position", rows.getInt("ORDINAL_POSITION"));
                columns.add(column);
            }
        }
        return columns;
    }

    private Set<String> readPrimaryKeys(
            DatabaseMetaData metadata, MetadataScope scope, String table, int maximum
    )
            throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet rows = metadata.getPrimaryKeys(scope.catalog(), scope.schema(), table)) {
            while (rows.next() && keys.size() < maximum) {
                keys.add(rows.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private List<Map<String, Object>> readForeignKeys(
            DatabaseMetaData metadata,
            MetadataScope scope,
            String table,
            int maximum,
            Set<String> allowedTables,
            String dialect
    )
            throws SQLException {
        List<Map<String, Object>> keys = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(scope.catalog(), scope.schema(), table)) {
            while (rows.next() && keys.size() < maximum) {
                String referencedDatabase = rows.getString("PKTABLE_CAT");
                String referencedSchema = rows.getString("PKTABLE_SCHEM");
                String referencedTable = rows.getString("PKTABLE_NAME");
                if ((StringUtils.isNotBlank(scope.catalog())
                        && !scope.catalog().equalsIgnoreCase(StringUtils.defaultString(referencedDatabase)))
                        || (StringUtils.isNotBlank(scope.schema())
                        && !scope.schema().equalsIgnoreCase(StringUtils.defaultString(referencedSchema)))
                        || isBlockedSystemScope(dialect, referencedDatabase, referencedSchema)
                        || StringUtils.isBlank(referencedTable)
                        || !allowedTables.contains(referencedTable.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("name", rows.getString("FK_NAME"));
                key.put("column", rows.getString("FKCOLUMN_NAME"));
                key.put("referencedSchema", referencedSchema);
                key.put("referencedTable", referencedTable);
                key.put("referencedColumn", rows.getString("PKCOLUMN_NAME"));
                keys.add(key);
            }
        }
        return keys;
    }

    private List<Map<String, Object>> readIndexes(
            DatabaseMetaData metadata, MetadataScope scope, String table, int maximum
    )
            throws SQLException {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (ResultSet rows = metadata.getIndexInfo(scope.catalog(), scope.schema(), table, false, false)) {
            while (rows.next() && indexes.size() < maximum) {
                String column = rows.getString("COLUMN_NAME");
                if (column == null) {
                    continue;
                }
                Map<String, Object> index = new LinkedHashMap<>();
                index.put("name", rows.getString("INDEX_NAME"));
                index.put("column", column);
                index.put("unique", !rows.getBoolean("NON_UNIQUE"));
                index.put("position", rows.getInt("ORDINAL_POSITION"));
                indexes.add(index);
            }
        }
        return indexes;
    }

    private MetadataScope metadataScope(
            Connection connection, AgentRequestContext context, String requestedSchema
    ) throws SQLException {
        String database = StringUtils.defaultIfBlank(context.database(), connection.getCatalog());
        String schema = normalizeMetadataSchema(
                database, StringUtils.defaultIfBlank(requestedSchema, context.schema())
        );
        if ("mysql".equals(context.dialect()) || "mariadb".equals(context.dialect())) {
            return new MetadataScope(StringUtils.defaultIfBlank(database, schema), null);
        }
        if ("oracle".equals(context.dialect()) || "dameng".equals(context.dialect())) {
            return new MetadataScope(null, StringUtils.defaultIfBlank(schema, safeSchema(connection)));
        }
        return new MetadataScope(database, StringUtils.defaultIfBlank(schema, safeSchema(connection)));
    }

    static String normalizeMetadataSchema(String database, String schema) {
        String normalized = StringUtils.trimToEmpty(schema);
        String catalog = StringUtils.trimToEmpty(database);
        if (StringUtils.isNotBlank(catalog)
                && StringUtils.startsWithIgnoreCase(normalized, catalog + ".")) {
            normalized = normalized.substring(catalog.length() + 1);
        }
        return normalized;
    }

    private static void assertRequestContextCurrent(Session session, AgentRequestContext expected) {
        ResourceNodeSnapshot node;
        String currentContext = "";
        if (StringUtils.isBlank(expected.consoleId())) {
            node = session.getDatasource().getResourceBrowser().getIndexedNode(expected.nodeKey());
        } else {
            var console = session.getConsoles().get(expected.consoleId());
            if (console == null || !expected.nodeKey().equals(console.getNodeKey())) {
                throw new IllegalStateException("The database workspace context has changed");
            }
            var consoleContext = console.getContext();
            node = new ResourceNodeSnapshot(
                    consoleContext.nodeKey(), consoleContext.nodeType(), consoleContext.database(),
                    consoleContext.schema(), consoleContext.table(), null
            );
            if (console instanceof QueryConsole queryConsole) {
                currentContext = queryConsole.getCurrentContext();
            }
        }
        if (node == null || !expected.nodeKey().equals(node.key())) {
            throw new IllegalStateException("The database workspace context has changed");
        }

        String database = node.database();
        String schema = node.schema();
        ConnectionManager manager = session.getDatasource().getConnectionManager();
        if (StringUtils.isNotBlank(currentContext)) {
            if (StringUtils.equals(manager.getContextKey(), manager.getDatabaseContextKey())) {
                database = currentContext;
            } else {
                schema = currentContext;
            }
        }
        schema = normalizeMetadataSchema(database, schema);
        if (!StringUtils.equals(database, expected.database())
                || !StringUtils.equalsIgnoreCase(schema, expected.schema())
                || !StringUtils.equals(currentContext, expected.currentContext())) {
            throw new IllegalStateException("The database workspace context has changed");
        }
    }

    private static void assertAllowedAiScope(
            String dialect,
            String database,
            String schema,
            String sql,
            Map<String, Object> sqlAnalysis
    ) {
        boolean sqlIsValid = sqlAnalysis == null || Boolean.TRUE.equals(sqlAnalysis.get("valid"));
        if (isBlockedSystemScope(dialect, database, schema)
                || (!sqlIsValid && containsBlockedSystemQualifier(dialect, sql))) {
            throw new IllegalArgumentException("System database objects are not available to the SQL assistant");
        }
        if (sqlAnalysis == null) {
            return;
        }
        Object tablesValue = sqlAnalysis.get("tables");
        if (!(tablesValue instanceof Collection<?> tables)) {
            return;
        }
        for (Object value : tables) {
            List<String> parts;
            try {
                parts = qualifiedIdentifierParts(String.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (parts.size() == 2 && (isBlockedSystemDatabase(dialect, parts.get(0))
                    || isBlockedSystemSchema(dialect, parts.get(0)))) {
                throw new IllegalArgumentException("System database objects are not available to the SQL assistant");
            }
            if (parts.size() == 3 && (isBlockedSystemDatabase(dialect, parts.get(0))
                    || isBlockedSystemSchema(dialect, parts.get(1)))) {
                throw new IllegalArgumentException("System database objects are not available to the SQL assistant");
            }
        }
    }

    private static boolean isBlockedSystemScope(String dialect, String database, String schema) {
        return isBlockedSystemDatabase(dialect, database) || isBlockedSystemSchema(dialect, schema);
    }

    private static boolean containsBlockedSystemQualifier(String dialect, String sql) {
        if (StringUtils.isBlank(sql)) {
            return false;
        }
        String normalized = sql.toLowerCase(Locale.ROOT)
                .replace("\"", "")
                .replace("`", "")
                .replace("[", "")
                .replace("]", "");
        Set<String> identifiers = new LinkedHashSet<>();
        identifiers.add("information_schema");
        String db = StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT);
        switch (db) {
            case "mysql", "mariadb" -> identifiers.addAll(Set.of("mysql", "performance_schema", "sys"));
            case "postgresql", "postgres" -> identifiers.addAll(Set.of("pg_catalog", "pg_toast"));
            case "sqlserver" -> identifiers.addAll(Set.of("master", "model", "msdb", "tempdb", "sys"));
            case "oracle" -> identifiers.addAll(Set.of("sys", "system", "xdb", "mdsys", "ctxsys", "audsys"));
            case "db2" -> identifiers.addAll(Set.of(
                    "sysibm", "syscat", "sysstat", "sysfun", "sysproc", "systools"
            ));
            case "clickhouse" -> identifiers.add("system");
            case "dm", "dameng" -> identifiers.addAll(Set.of("sys", "system", "sysauditor"));
            default -> {
            }
        }
        for (String identifier : identifiers) {
            Pattern qualifier = Pattern.compile(
                    "(?<![a-z0-9_$])" + Pattern.quote(identifier) + "\\s*\\."
            );
            if (qualifier.matcher(normalized).find()) {
                return true;
            }
        }
        return Set.of("postgresql", "postgres").contains(db)
                && Pattern.compile("(?<![a-z0-9_$])pg_(?:temp|toast_temp)_[a-z0-9_$]+\\s*\\.")
                .matcher(normalized)
                .find();
    }

    private static boolean isBlockedSystemDatabase(String dialect, String database) {
        String value = normalizePolicyIdentifier(database);
        String db = StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        if ("information_schema".equals(value)) {
            return true;
        }
        return switch (db) {
            case "mysql", "mariadb" -> Set.of("mysql", "performance_schema", "sys").contains(value);
            case "sqlserver" -> Set.of("master", "model", "msdb", "tempdb").contains(value);
            case "clickhouse" -> "system".equals(value);
            default -> false;
        };
    }

    private static boolean isBlockedSystemSchema(String dialect, String schema) {
        String value = normalizePolicyIdentifier(schema);
        String db = StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        if ("information_schema".equals(value)) {
            return true;
        }
        return switch (db) {
            case "postgresql", "postgres" -> value.equals("pg_catalog")
                    || value.equals("pg_toast")
                    || value.startsWith("pg_temp_")
                    || value.startsWith("pg_toast_temp_");
            case "sqlserver" -> value.equals("sys");
            case "oracle" -> Set.of(
                    "sys", "system", "xdb", "mdsys", "ctxsys", "audsys"
            ).contains(value);
            case "db2" -> Set.of(
                    "sysibm", "syscat", "sysstat", "sysfun", "sysproc", "systools"
            ).contains(value);
            case "clickhouse" -> value.equals("system");
            case "dm", "dameng" -> Set.of("sys", "system", "sysauditor").contains(value);
            default -> false;
        };
    }

    private static String normalizePolicyIdentifier(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        if (normalized.length() >= 2 && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("`") && normalized.endsWith("`"))
                || (normalized.startsWith("[") && normalized.endsWith("]")))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean requiresSchemaScope(String dialect) {
        return !Set.of("mysql", "mariadb").contains(
                StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT)
        );
    }

    private static boolean requiresDatabaseScope(String dialect) {
        return Set.of("mysql", "mariadb", "clickhouse", "sqlserver").contains(
                StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT)
        );
    }

    private static JsonObject connectionContext(
            String dialect,
            String database,
            String displaySchema,
            String resolvedSchema,
            String currentContext,
            String contextKey,
            String databaseContextKey,
            ResourceNodeSnapshot node
    ) {
        JsonObject result = new JsonObject();
        result.addProperty("dialect", StringUtils.defaultString(dialect));
        addNullableString(result, "database", database);
        addNullableString(result, "displaySchema", displaySchema);
        addNullableString(result, "resolvedSchema", resolvedSchema);
        addNullableString(result, "currentContext", currentContext);
        result.addProperty("contextKey", StringUtils.defaultString(contextKey));
        result.addProperty("databaseContextKey", StringUtils.defaultString(databaseContextKey));
        result.addProperty("nodeType", StringUtils.defaultString(node.type()));
        addNullableString(result, "table", node.table());
        result.addProperty("identifierQuote", identifierQuote(dialect));
        result.addProperty("draftOnly", true);
        result.addProperty("businessRowAccess", false);
        return result;
    }

    private static String identifierQuote(String dialect) {
        return switch (StringUtils.defaultString(dialect).toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb", "clickhouse" -> "`";
            case "sqlserver" -> "[]";
            default -> "\"";
        };
    }

    private static Map<String, Object> jdbcConnectionContext(
            DatabaseMetaData metadata,
            AgentRequestContext context
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dialect", context.dialect());
        result.put("databaseProduct", safeMetadataString(metadata::getDatabaseProductName));
        result.put("databaseVersion", safeMetadataString(metadata::getDatabaseProductVersion));
        result.put("driverName", safeMetadataString(metadata::getDriverName));
        result.put("driverVersion", safeMetadataString(metadata::getDriverVersion));
        result.put("identifierQuote", safeMetadataString(metadata::getIdentifierQuoteString));
        return result;
    }

    private static Map<String, Object> metadataScopeMap(String catalog, String schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", StringUtils.defaultString(catalog));
        result.put("schema", StringUtils.defaultString(schema));
        return result;
    }

    private static String safeMetadataString(MetadataStringReader reader) {
        try {
            return StringUtils.defaultString(reader.read()).trim();
        } catch (SQLException | AbstractMethodError | RuntimeException ignored) {
            return "";
        }
    }

    @FunctionalInterface
    private interface MetadataStringReader {
        String read() throws SQLException;
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private static String escapeMetadataPattern(String input, String escape) {
        String escaped = input;
        if (StringUtils.isNotEmpty(escape)) {
            escaped = escaped.replace(escape, escape + escape)
                    .replace("%", escape + "%")
                    .replace("_", escape + "_");
        }
        return escaped;
    }

    private static String statementType(SQLStatement statement) {
        String name = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        if (name.startsWith("SQL")) {
            name = name.substring(3);
        }
        if (name.endsWith("STATEMENT")) {
            name = name.substring(0, name.length() - "STATEMENT".length());
        }
        return name;
    }

    private static int riskLevel(String statementType) {
        String type = statementType.toUpperCase(Locale.ROOT);
        if (type.contains("SELECT") || type.contains("SHOW") || type.contains("DESC")
                || type.contains("EXPLAIN") || type.contains("WITH")) {
            return 1;
        }
        if (type.contains("INSERT") || type.contains("UPDATE") || type.contains("DELETE")
                || type.contains("MERGE") || type.contains("REPLACE")) {
            return 3;
        }
        if (type.contains("DROP") || type.contains("TRUNCATE") || type.contains("GRANT")
                || type.contains("REVOKE")) {
            return 4;
        }
        if (type.contains("CREATE") || type.contains("ALTER") || type.contains("RENAME")) {
            return 3;
        }
        return 2;
    }

    private static String riskReason(int riskLevel, int statementCount) {
        String base = switch (riskLevel) {
            case 1 -> "Read-only SQL statement";
            case 3 -> "SQL may change database data or schema";
            case 4 -> "SQL may remove data or change privileges";
            default -> "SQL statement requires manual review";
        };
        return statementCount > 1 ? base + "; contains multiple statements" : base;
    }

    private static String safeError(RuntimeException error) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
        return message.length() > 4096 ? message.substring(0, 4096) : message;
    }

    private static void addBounded(Set<String> values, String value, int max) {
        if (values.size() < max && StringUtils.isNotBlank(value)) {
            values.add(value);
        }
    }

    static JsonObject sanitizeLastError(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        JsonObject submitted;
        try {
            submitted = value.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL error context", e);
        }
        JsonObject sanitized = new JsonObject();
        sanitized.addProperty("kind", boundedString(submitted, "kind", 32, false));
        sanitized.addProperty("title", boundedString(submitted, "title", 256, false));
        sanitized.addProperty("message", boundedString(submitted, "message", 8 * 1024, true));
        sanitized.addProperty("sql", boundedString(submitted, "sql", MAX_SQL_BYTES, false));
        sanitized.addProperty("sqlState", boundedString(submitted, "sqlState", 128, false));
        try {
            if (submitted.has("vendorCode") && !submitted.get("vendorCode").isJsonNull()) {
                sanitized.addProperty("vendorCode", submitted.get("vendorCode").getAsInt());
            }
            if (submitted.has("timestamp") && !submitted.get("timestamp").isJsonNull()) {
                sanitized.addProperty("timestamp", submitted.get("timestamp").getAsLong());
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL error context", e);
        }
        return sanitized;
    }

    private static int nonNegativeInt(JsonObject object, String name) {
        if (!object.has(name)) {
            return 0;
        }
        int value = object.get(name).getAsInt();
        if (value < 0) {
            throw new IllegalArgumentException("Invalid SQL editor selection");
        }
        return value;
    }

    private static List<String> boundedStringList(JsonObject object, String name, int maximum) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (JsonElement element : value.getAsJsonArray()) {
                String text = element.getAsString().trim();
                if (text.isEmpty() || text.length() > MAX_IDENTIFIER_BYTES) {
                    throw new IllegalArgumentException("Invalid " + name);
                }
                values.putIfAbsent(text.toLowerCase(Locale.ROOT), text);
                if (values.size() > maximum) {
                    throw new IllegalArgumentException("Too many " + name);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid " + name, e);
        }
        return List.copyOf(values.values());
    }

    private static String unqualifiedTableName(String value) {
        List<String> parts = qualifiedIdentifierParts(value);
        return parts.get(parts.size() - 1);
    }

    private static List<String> qualifiedIdentifierParts(String value) {
        String input = StringUtils.trimToEmpty(value);
        if (input.isBlank()) {
            throw new IllegalArgumentException("Invalid table name");
        }
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (quote == 0 && (current == '"' || current == '`' || current == '[')) {
                quote = current == '[' ? ']' : current;
                part.append(current);
                continue;
            }
            if (quote != 0 && current == quote) {
                if (index + 1 < input.length() && input.charAt(index + 1) == quote) {
                    part.append(current).append(current);
                    index++;
                    continue;
                }
                quote = 0;
                part.append(current);
                continue;
            }
            if (quote == 0 && current == '.') {
                parts.add(normalizeIdentifierPart(part.toString()));
                part.setLength(0);
                continue;
            }
            part.append(current);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Invalid table name");
        }
        parts.add(normalizeIdentifierPart(part.toString()));
        if (parts.size() > 3) {
            throw new IllegalArgumentException("Invalid table name");
        }
        return List.copyOf(parts);
    }

    private static String normalizeIdentifierPart(String value) {
        String result = StringUtils.trimToEmpty(value);
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("`") && result.endsWith("`"))
                || (result.startsWith("[") && result.endsWith("]")))) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.isBlank() || result.length() > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException("Invalid table name");
        }
        return result;
    }

    private static void validateTableQualifier(List<String> parts, String database, String schema) {
        if (parts.size() >= 2) {
            String qualifier = parts.get(parts.size() - 2);
            if (!qualifier.equalsIgnoreCase(StringUtils.defaultString(schema))
                    && !qualifier.equalsIgnoreCase(StringUtils.defaultString(database))) {
                throw new IllegalArgumentException("Table inspection must stay in the active schema");
            }
        }
        if (parts.size() == 3
                && !parts.get(0).equalsIgnoreCase(StringUtils.defaultString(database))) {
            throw new IllegalArgumentException("Table inspection must stay in the active database");
        }
    }

    private static String boundedString(JsonObject object, String name, int maximum, boolean required) {
        JsonElement value = object.get(name);
        String text = value == null || value.isJsonNull() ? "" : value.getAsString();
        if ((required && text.isBlank()) || text.length() > maximum) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }

    private static void addNullableString(JsonObject object, String name, String value) {
        object.addProperty(name, StringUtils.defaultString(value));
    }

    public record AgentRequestContext(
            String dialect,
            String database,
            String schema,
            String table,
            String nodeKey,
            String consoleId,
            String currentContext,
            String sanitizedJson
    ) {
    }

    record MetadataApprovalScope(
            String database,
            String schema,
            String nodeKey,
            List<String> tables,
            String query,
            boolean discovery
    ) {
        boolean covers(MetadataApprovalScope requested) {
            if (requested == null
                    || !database.equalsIgnoreCase(requested.database)
                    || !schema.equalsIgnoreCase(requested.schema)
                    || !nodeKey.equals(requested.nodeKey)) {
                return false;
            }
            if (discovery) {
                return requested.discovery || (requested.query.isBlank()
                        && !requested.tables.isEmpty()
                        && requested.tables.size() <= MAX_INSPECT_TABLES);
            }
            if (!query.equalsIgnoreCase(requested.query)) {
                return false;
            }
            Set<String> allowedTables = tables.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            return requested.tables.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .allMatch(allowedTables::contains);
        }
    }

    private record MetadataScope(String catalog, String schema) {
    }
}
