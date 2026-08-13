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

@Service
public class SqlAgentToolService {
    private static final Gson GSON = new Gson();
    private static final int MAX_CONTEXT_BYTES = 256 * 1024;
    private static final int MAX_SQL_BYTES = 128 * 1024;
    private static final int MAX_IDENTIFIER_BYTES = 1024;
    private static final int MAX_OBJECTS = 50;
    private static final int MAX_ANALYSIS_COLUMNS = 512;
    private static final int MAX_INSPECT_TABLES = 8;
    private static final int MAX_INSPECT_COLUMNS = 96;
    private static final int MAX_INSPECT_RELATIONS = 48;
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
        if (console instanceof QueryConsole queryConsole) {
            currentContext = queryConsole.getCurrentContext();
            ConnectionManager manager = session.getDatasource().getConnectionManager();
            if (StringUtils.isNotBlank(currentContext)) {
                if (StringUtils.equals(manager.getContextKey(), manager.getDatabaseContextKey())) {
                    database = currentContext;
                } else {
                    schema = currentContext;
                }
            }
        }

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
        JsonObject sanitized = new JsonObject();
        sanitized.addProperty("dialect", dialect);
        addNullableString(sanitized, "database", database);
        addNullableString(sanitized, "schema", schema);
        sanitized.addProperty("nodeKey", node.key());
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
                sanitizedJson
        );
    }

    public String execute(Session session, AgentRequestContext context, String toolName, String argumentsJson)
            throws SQLException {
        if (session == null || context == null || session.isClosing() || !session.isActive()) {
            throw new IllegalStateException("The database session is not active");
        }
        toolName = StringUtils.defaultString(toolName).trim().toLowerCase(Locale.ROOT);
        if (!TOOL_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("Unsupported SQL assistant tool");
        }
        if (argumentsJson == null || argumentsJson.length() > 32 * 1024) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments");
        }
        JsonObject arguments;
        try {
            arguments = JsonParser.parseString(argumentsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments", e);
        }

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
        String query = boundedString(arguments, "query", MAX_IDENTIFIER_BYTES, false).trim();
        List<String> requestedTables = boundedStringList(arguments, "tables", MAX_INSPECT_TABLES);
        if (query.isBlank() && requestedTables.isEmpty()) {
            throw new IllegalArgumentException("Schema inspection query or tables are required");
        }
        String requestedSchema = boundedString(arguments, "schema", MAX_IDENTIFIER_BYTES, false);
        try (Connection connection = manager.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            MetadataScope scope = metadataScope(connection, context, requestedSchema);
            Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
            List<String> missingTables = new ArrayList<>();
            for (String requestedTable : requestedTables) {
                String table = unqualifiedTableName(requestedTable);
                Map<String, Object> item = findTable(metadata, scope, table);
                if (item == null) {
                    missingTables.add(requestedTable);
                } else {
                    candidates.putIfAbsent(metadataKey(item), item);
                }
            }
            if (!query.isBlank() && candidates.size() < MAX_INSPECT_TABLES) {
                String pattern = "%" + escapeMetadataPattern(query, metadata.getSearchStringEscape()) + "%";
                try (ResultSet rows = metadata.getTables(
                        scope.catalog(), scope.schema(), pattern,
                        new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"}
                )) {
                    while (rows.next() && candidates.size() < MAX_INSPECT_TABLES) {
                        Map<String, Object> item = tableInfo(rows);
                        candidates.putIfAbsent(metadataKey(item), item);
                    }
                }
            }
            List<Map<String, Object>> objects = new ArrayList<>(candidates.size());
            for (Map<String, Object> tableInfo : candidates.values()) {
                objects.add(describeTable(metadata, scope, tableInfo));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("objects", objects);
            result.put("missingTables", missingTables);
            result.put("truncated", candidates.size() == MAX_INSPECT_TABLES);
            return result;
        }
    }

    private Map<String, Object> describeTable(
            DatabaseMetaData metadata, MetadataScope scope, Map<String, Object> tableInfo
    ) throws SQLException {
        String table = String.valueOf(tableInfo.get("name"));
        List<Map<String, Object>> columns = readColumns(metadata, scope, table, MAX_INSPECT_COLUMNS);
        Set<String> primaryKeys = readPrimaryKeys(metadata, scope, table, MAX_INSPECT_COLUMNS);
        for (Map<String, Object> column : columns) {
            column.put("primaryKey", primaryKeys.contains(column.get("name")));
        }
        Map<String, Object> result = new LinkedHashMap<>(tableInfo);
        result.put("columns", columns);
        result.put("foreignKeys", readForeignKeys(metadata, scope, table, MAX_INSPECT_RELATIONS));
        result.put("indexes", readIndexes(metadata, scope, table, MAX_INSPECT_RELATIONS));
        result.put("truncated", columns.size() == MAX_INSPECT_COLUMNS);
        return result;
    }

    private Map<String, Object> findTable(DatabaseMetaData metadata, MetadataScope scope, String table)
            throws SQLException {
        String pattern = escapeMetadataPattern(table, metadata.getSearchStringEscape());
        try (ResultSet rows = metadata.getTables(
                scope.catalog(), scope.schema(), pattern,
                new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"}
        )) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    return tableInfo(rows);
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
            DatabaseMetaData metadata, MetadataScope scope, String table, int maximum
    )
            throws SQLException {
        List<Map<String, Object>> keys = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(scope.catalog(), scope.schema(), table)) {
            while (rows.next() && keys.size() < maximum) {
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("name", rows.getString("FK_NAME"));
                key.put("column", rows.getString("FKCOLUMN_NAME"));
                key.put("referencedSchema", rows.getString("PKTABLE_SCHEM"));
                key.put("referencedTable", rows.getString("PKTABLE_NAME"));
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
        String schema = StringUtils.defaultIfBlank(requestedSchema, context.schema());
        String database = StringUtils.defaultIfBlank(context.database(), connection.getCatalog());
        if ("mysql".equals(context.dialect()) || "mariadb".equals(context.dialect())) {
            return new MetadataScope(StringUtils.defaultIfBlank(database, schema), null);
        }
        if ("oracle".equals(context.dialect()) || "dameng".equals(context.dialect())) {
            return new MetadataScope(null, StringUtils.defaultIfBlank(schema, safeSchema(connection)));
        }
        return new MetadataScope(database, StringUtils.defaultIfBlank(schema, safeSchema(connection)));
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
        String result = StringUtils.trimToEmpty(value);
        int separator = result.lastIndexOf('.');
        if (separator >= 0) {
            result = result.substring(separator + 1).trim();
        }
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("`") && result.endsWith("`"))
                || (result.startsWith("[") && result.endsWith("]")))) {
            result = result.substring(1, result.length() - 1);
        }
        if (result.isBlank()) {
            throw new IllegalArgumentException("Invalid table name");
        }
        return result;
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
            String sanitizedJson
    ) {
    }

    private record MetadataScope(String catalog, String schema) {
    }
}
