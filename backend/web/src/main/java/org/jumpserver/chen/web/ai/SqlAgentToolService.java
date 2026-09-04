package org.jumpserver.chen.web.ai;

import com.alibaba.druid.DbType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.ConsoleStatementBoundaryScanner;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DatasourceInfo;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ForeignKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexPart;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.PrimaryKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.sql.SqlValidationResult;
import org.jumpserver.chen.framework.datasource.sql.SqlValidator;
import org.jumpserver.chen.framework.session.Session;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SqlAgentToolService {
    static final String CONSOLE_UNPARSEABLE_NOTICE = "Chen cannot validate this SQL with Druid.\n\n"
            + "It may use database-native syntax.\n\n"
            + "Please review it before execution.";
    private static final Gson GSON = new Gson();
    private static final int MAX_CONTEXT_BYTES = 256 * 1024;
    private static final int MAX_SQL_BYTES = 128 * 1024;
    private static final int MAX_TOOL_ARGUMENT_BYTES = MAX_SQL_BYTES + 8 * 1024;
    private static final int MAX_IDENTIFIER_BYTES = 1024;
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
    private static final Set<String> TOOL_NAMES =
            Set.of("read_sql_context", "inspect_schema", "validate_sql", "propose_sql");
    private static final Set<RelationKind> INSPECT_KINDS =
            Set.of(RelationKind.TABLE, RelationKind.VIEW, RelationKind.MATERIALIZED_VIEW);
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
        if (argumentsJson == null || argumentsJson.length() > MAX_TOOL_ARGUMENT_BYTES) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments");
        }
        JsonObject arguments = parseToolArguments(argumentsJson);

        Datasource datasource = session.getDatasource();
        ConnectionManager connectionManager = datasource.getConnectionManager();
        connectionManager.setDatabaseContext(StringUtils.defaultString(context.database()));
        Object result = switch (toolName) {
            case "read_sql_context" -> Map.of(
                    "kind", "sql_context",
                    "context", JsonParser.parseString(context.sanitizedJson()).getAsJsonObject()
            );
            case "inspect_schema" -> Map.of(
                    "kind", "schema",
                    "schema", inspectSchema(datasource, context, arguments)
            );
            case "validate_sql" -> Map.of(
                    "kind", "validation",
                    "analysis", SqlValidator.validate(
                            datasource.getDruidDbType(), boundedString(arguments, "sql", MAX_SQL_BYTES, true)
                    ).toAnalysisMap()
            );
            case "propose_sql" -> proposeSQL(datasource, context, arguments);
            default -> throw new IllegalArgumentException("Unsupported SQL assistant tool");
        };
        return GSON.toJson(result);
    }

    private static Map<String, Object> proposeSQL(
            Datasource datasource,
            AgentRequestContext context,
            JsonObject arguments
    ) {
        String sql = boundedString(arguments, "sql", MAX_SQL_BYTES, true).trim();
        String explanation = boundedString(arguments, "explanation", 4 * 1024, false).trim();
        JsonObject editor = JsonParser.parseString(context.sanitizedJson()).getAsJsonObject();
        boolean consoleWorkspace = "console".equals(boundedString(editor, "workspaceTabKind", 32, false));
        SqlValidationResult validation = SqlValidator.validate(datasource.getDruidDbType(), sql);
        if (!validation.parseable()) {
            if (!consoleWorkspace) {
                throw new IllegalArgumentException(SqlValidator.QUERY_UNSUPPORTED_MESSAGE);
            }
            assertSingleConsoleStatement(sql);
            explanation = appendNotice(explanation, CONSOLE_UNPARSEABLE_NOTICE);
        } else if (validation.statementCount() != 1) {
            throw new IllegalArgumentException("The SQL proposal must contain exactly one valid statement");
        }
        Map<String, Object> analysis = validation.parseable() ? validation.toAnalysisMap() : null;
        int selectionFrom = editor.get("selectionFrom").getAsInt();
        int selectionTo = editor.get("selectionTo").getAsInt();
        String tabId = editor.get("tabId").getAsString();
        String target = selectionTo > selectionFrom ? "selection" : tabId.isBlank() ? "new_query" : "document";
        String originalSQL = "selection".equals(target)
                ? editor.get("selectedSql").getAsString()
                : "document".equals(target) ? editor.get("documentSql").getAsString() : "";
        if (!"new_query".equals(target) && sql.equals(originalSQL.trim())) {
            throw new IllegalArgumentException("The SQL proposal does not change the current editor target");
        }

        Map<String, Object> base = new LinkedHashMap<>();
        for (String field : List.of(
                "paneId", "tabId", "workspaceTabId", "workspaceTabKind", "currentContext",
                "revision", "selectionFrom", "selectionTo", "nodeKey", "database", "schema"
        )) {
            JsonElement value = editor.get(field);
            if (value != null && !value.isJsonNull()) {
                base.put(field, GSON.fromJson(value, Object.class));
            }
        }
        base.put("target", target);

        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("sql", sql);
        proposal.put("originalSql", originalSQL);
        proposal.put("explanation", explanation);
        if (analysis != null) {
            proposal.put("analysis", analysis);
        }
        proposal.put("base", base);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "proposal");
        if (analysis != null) {
            result.put("analysis", analysis);
        }
        result.put("proposal", proposal);
        return result;
    }

    private static String appendNotice(String explanation, String notice) {
        return explanation.isBlank() ? notice : explanation + "\n\n" + notice;
    }

    private static void assertSingleConsoleStatement(String sql) {
        try {
            if (!ConsoleStatementBoundaryScanner.hasExactlyOneStatement(sql)) {
                throw new IllegalArgumentException("The SQL proposal must contain exactly one statement");
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("The SQL proposal must contain exactly one statement", e);
        }
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
            throw new IllegalArgumentException(blockedSystemScopeMessage(
                    context.dialect(), context.database(), activeSchema
            ));
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
        if (argumentsJson == null || argumentsJson.length() > MAX_TOOL_ARGUMENT_BYTES) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments");
        }
        try {
            return JsonParser.parseString(argumentsJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SQL assistant tool arguments", e);
        }
    }

    static Map<String, Object> validateSQL(DbType dbType, String sql) {
        return SqlValidator.validate(dbType, sql).toAnalysisMap();
    }

    private Map<String, Object> inspectSchema(
            Datasource datasource, AgentRequestContext context, JsonObject arguments
    ) throws SQLException {
        MetadataApprovalScope approvedScope = resolveMetadataApprovalScope(context, arguments);
        MetadataCatalog catalog = datasource.getMetadataCatalog();
        if (catalog == null) {
            throw new IllegalStateException("Database metadata catalog is not available");
        }
        RelationScope scope = relationScope(context, approvedScope.schema());
        List<RelationMetadata> available = catalog.listRelations(scope, INSPECT_KINDS).stream()
                .filter(relation -> isRelationWithinScope(relation.ref(), scope, context.dialect()))
                .toList();

        Map<String, RelationMetadata> candidates = new LinkedHashMap<>();
        List<String> missingTables = new ArrayList<>();
        for (String requestedTable : approvedScope.tables()) {
            String table = unqualifiedTableName(requestedTable);
            RelationMetadata match = findRelation(available, table);
            if (match == null) {
                missingTables.add(requestedTable);
            } else {
                candidates.putIfAbsent(metadataKey(match.ref()), match);
            }
        }

        String query = approvedScope.query();
        int maximumTables = approvedScope.discovery() ? MAX_DISCOVER_TABLES : MAX_INSPECT_TABLES;
        boolean truncated = false;
        if (!query.isBlank()) {
            String needle = approvedScope.discovery() ? "" : query.toLowerCase(Locale.ROOT);
            List<RelationMetadata> matches = available.stream()
                    .filter(relation -> needle.isEmpty()
                            || relation.ref().name().toLowerCase(Locale.ROOT).contains(needle))
                    .filter(relation -> !candidates.containsKey(metadataKey(relation.ref())))
                    .toList();
            truncated = candidates.size() + matches.size() > maximumTables;
            for (RelationMetadata match : matches) {
                if (candidates.size() >= maximumTables) {
                    break;
                }
                candidates.putIfAbsent(metadataKey(match.ref()), match);
            }
        }

        Set<String> allowedTables = candidates.values().stream()
                .map(relation -> relation.ref().name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Map<String, Object>> objects = new ArrayList<>(candidates.size());
        if (approvedScope.discovery()) {
            for (RelationMetadata relation : candidates.values()) {
                objects.add(tableInfo(relation));
            }
        } else {
            objects.addAll(describeRelations(
                    catalog, scope, List.copyOf(candidates.values()), allowedTables, context.dialect()
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", inspectConnectionBanner(datasource, context));
        result.put("requestedScope", metadataScopeMap(context.database(), approvedScope.schema()));
        result.put("resolvedScope", metadataScopeMap(scope.catalog(), scope.schema()));
        result.put("objects", objects);
        result.put("missingTables", missingTables);
        result.put("matchCount", objects.size());
        result.put("discovery", approvedScope.discovery());
        result.put("truncated", truncated);
        return result;
    }

    private List<Map<String, Object>> describeRelations(
            MetadataCatalog catalog,
            RelationScope scope,
            List<RelationMetadata> relations,
            Set<String> allowedTables,
            String dialect
    ) throws SQLException {
        List<ObjectRef> refs = relations.stream().map(RelationMetadata::ref).toList();
        Map<String, List<ColumnMetadata>> columnsByOwner = new LinkedHashMap<>();
        for (ColumnMetadata column : catalog.listColumns(refs)) {
            columnsByOwner.computeIfAbsent(metadataKey(column.owner()), ignored -> new ArrayList<>()).add(column);
        }
        Map<String, Set<String>> primaryKeysByOwner = new LinkedHashMap<>();
        for (PrimaryKeyMetadata primaryKey : catalog.listPrimaryKeys(refs)) {
            primaryKeysByOwner.computeIfAbsent(metadataKey(primaryKey.owner()), ignored -> new LinkedHashSet<>())
                    .addAll(primaryKey.columns());
        }
        Map<String, List<ForeignKeyMetadata>> foreignKeysByOwner = new LinkedHashMap<>();
        for (ForeignKeyMetadata foreignKey : catalog.listForeignKeys(refs)) {
            foreignKeysByOwner.computeIfAbsent(metadataKey(foreignKey.owner()), ignored -> new ArrayList<>())
                    .add(foreignKey);
        }
        Map<String, List<IndexMetadata>> indexesByOwner = new LinkedHashMap<>();
        for (IndexMetadata index : catalog.listIndexes(scope)) {
            indexesByOwner.computeIfAbsent(metadataKey(index.owner()), ignored -> new ArrayList<>()).add(index);
        }

        List<Map<String, Object>> objects = new ArrayList<>(relations.size());
        for (RelationMetadata relation : relations) {
            String key = metadataKey(relation.ref());
            List<Map<String, Object>> columns = serializeColumns(
                    columnsByOwner.getOrDefault(key, List.of()),
                    primaryKeysByOwner.getOrDefault(key, Set.of())
            );
            Map<String, Object> item = tableInfo(relation);
            item.put("columns", columns);
            item.put("foreignKeys", serializeForeignKeys(
                    foreignKeysByOwner.getOrDefault(key, List.of()),
                    scope, allowedTables, dialect
            ));
            item.put("indexes", serializeIndexes(indexesByOwner.getOrDefault(key, List.of())));
            item.put("truncated", columns.size() == MAX_INSPECT_COLUMNS);
            objects.add(item);
        }
        return objects;
    }

    private static List<Map<String, Object>> serializeColumns(
            List<ColumnMetadata> columns, Set<String> primaryKeys
    ) {
        Set<String> primaryKeyNames = primaryKeys.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (result.size() >= MAX_INSPECT_COLUMNS) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.name());
            item.put("type", column.nativeType());
            item.put("jdbcType", column.jdbcType());
            item.put("size", column.size());
            item.put("scale", column.scale());
            item.put("nullable", column.nullable());
            item.put("defaultValue", column.defaultValue());
            item.put("comment", column.comment());
            item.put("position", column.ordinal());
            item.put("primaryKey", primaryKeyNames.contains(StringUtils.defaultString(column.name()).toLowerCase(Locale.ROOT)));
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> serializeForeignKeys(
            List<ForeignKeyMetadata> foreignKeys,
            RelationScope scope,
            Set<String> allowedTables,
            String dialect
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ForeignKeyMetadata foreignKey : foreignKeys) {
            ObjectRef referenced = foreignKey.referenced();
            if (referenced == null
                    || !isRelationWithinScope(referenced, scope, dialect)
                    || !allowedTables.contains(StringUtils.defaultString(referenced.name()).toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> columns = foreignKey.columns() == null ? List.of() : foreignKey.columns();
            List<String> referencedColumns = foreignKey.referencedColumns() == null
                    ? List.of() : foreignKey.referencedColumns();
            int count = Math.min(columns.size(), referencedColumns.size());
            for (int index = 0; index < count && result.size() < MAX_INSPECT_RELATIONS; index++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", foreignKey.name());
                item.put("column", columns.get(index));
                item.put("referencedSchema", referenced.schema());
                item.put("referencedTable", referenced.name());
                item.put("referencedColumn", referencedColumns.get(index));
                result.add(item);
            }
            if (result.size() >= MAX_INSPECT_RELATIONS) {
                break;
            }
        }
        return result;
    }

    private static List<Map<String, Object>> serializeIndexes(List<IndexMetadata> indexes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IndexMetadata index : indexes) {
            List<IndexPart> parts = index.parts() == null ? List.of() : index.parts();
            for (IndexPart part : parts) {
                String column = part.columnName() != null ? part.columnName() : part.expression();
                if (column == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", index.name());
                item.put("column", column);
                item.put("unique", index.unique());
                item.put("position", part.ordinal());
                result.add(item);
                if (result.size() >= MAX_INSPECT_RELATIONS) {
                    return result;
                }
            }
        }
        return result;
    }

    private static RelationMetadata findRelation(List<RelationMetadata> relations, String table) {
        for (RelationMetadata relation : relations) {
            if (table.equalsIgnoreCase(relation.ref().name())) {
                return relation;
            }
        }
        return null;
    }

    private static Map<String, Object> tableInfo(RelationMetadata relation) {
        ObjectRef ref = relation.ref();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", ref.catalog());
        result.put("schema", ref.schema());
        result.put("name", ref.name());
        result.put("type", jdbcRelationType(ref.kind()));
        result.put("comment", relation.comment());
        return result;
    }

    private static String jdbcRelationType(RelationKind kind) {
        if (kind == RelationKind.MATERIALIZED_VIEW) {
            return "MATERIALIZED VIEW";
        }
        if (kind == RelationKind.VIEW) {
            return "VIEW";
        }
        return "TABLE";
    }

    private static boolean isRelationWithinScope(ObjectRef ref, RelationScope scope, String dialect) {
        if (ref == null) {
            return false;
        }
        String database = StringUtils.defaultString(ref.catalog());
        String schema = StringUtils.defaultString(ref.schema());
        if (StringUtils.isNotBlank(scope.catalog()) && !scope.catalog().equalsIgnoreCase(database)) {
            return false;
        }
        if (StringUtils.isNotBlank(scope.schema()) && !scope.schema().equalsIgnoreCase(schema)) {
            return false;
        }
        return !isBlockedSystemScope(dialect, database, schema);
    }

    private static String metadataKey(ObjectRef ref) {
        return (StringUtils.defaultString(ref.catalog()) + "\u0000"
                + StringUtils.defaultString(ref.schema()) + "\u0000"
                + StringUtils.defaultString(ref.name())).toLowerCase(Locale.ROOT);
    }

    private static RelationScope relationScope(AgentRequestContext context, String requestedSchema) {
        String database = StringUtils.trimToNull(context.database());
        String schema = StringUtils.trimToNull(normalizeMetadataSchema(
                StringUtils.defaultString(database),
                StringUtils.defaultIfBlank(requestedSchema, context.schema())
        ));
        String dialect = StringUtils.defaultString(context.dialect()).toLowerCase(Locale.ROOT);
        if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
            return new RelationScope(database != null ? database : schema, null);
        }
        if ("oracle".equals(dialect) || "dameng".equals(dialect) || "dm".equals(dialect)) {
            return new RelationScope(null, schema);
        }
        return new RelationScope(database, schema);
    }

    private static Map<String, Object> inspectConnectionBanner(Datasource datasource, AgentRequestContext context) {
        DatasourceInfo info = datasource.getInfo();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dialect", context.dialect());
        result.put("databaseProduct", info == null ? "" : StringUtils.defaultString(info.getDbType()));
        result.put("databaseVersion", info == null ? "" : StringUtils.defaultString(info.getVersion()));
        result.put("driverName", info == null ? "" : StringUtils.defaultString(info.getDriverClassName()));
        result.put("driverVersion", info == null ? "" : StringUtils.defaultString(info.getDriverVersion()));
        result.put("identifierQuote", identifierQuote(context.dialect()));
        return result;
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

    private static String blockedSystemScopeMessage(String dialect, String database, String schema) {
        if (isBlockedSystemSchema(dialect, schema)) {
            return "The active schema '" + normalizePolicyIdentifier(schema)
                    + "' is a protected system schema; table metadata is unavailable in this scope";
        }
        if (isBlockedSystemDatabase(dialect, database)) {
            return "The active database '" + normalizePolicyIdentifier(database)
                    + "' is a protected system database; table metadata is unavailable in this scope";
        }
        return "The active database scope contains protected system objects";
    }

    private static boolean isBlockedSystemScope(String dialect, String database, String schema) {
        return isBlockedSystemDatabase(dialect, database) || isBlockedSystemSchema(dialect, schema);
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

    private static Map<String, Object> metadataScopeMap(String catalog, String schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", StringUtils.defaultString(catalog));
        result.put("schema", StringUtils.defaultString(schema));
        return result;
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
