package org.jumpserver.chen.framework.datasource.metadata;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.utils.SqlIdentifierUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SqlMetadataCatalog {
    public static final int DEFAULT_RELATION_LIMIT = 100;
    public static final int MAX_RELATION_LIMIT = 200;
    public static final int MAX_COLUMN_RELATIONS = 20;

    private static final Set<String> RELATION_KINDS = Set.of("table", "view");

    private final ResourceBrowser resourceBrowser;
    private final ConnectionManager connectionManager;

    public SqlMetadataCatalog(ResourceBrowser resourceBrowser, ConnectionManager connectionManager) {
        this.resourceBrowser = Objects.requireNonNull(resourceBrowser);
        this.connectionManager = Objects.requireNonNull(connectionManager);
    }

    public RelationMetadataPage listRelations(ResourceNodeSnapshot node, String context, String prefix, Integer limit)
            throws SQLException {
        var scope = this.resolveScope(node, context);
        var schemas = this.resolveSchemas(scope.schema());
        var relations = new ArrayList<QualifiedRelation>();

        for (var schema : schemas) {
            resourceBrowser.getTables(schema).forEach(table -> relations.add(
                    new QualifiedRelation(scope.catalog(), schema, table.getName(), "table")
            ));
            resourceBrowser.getViews(schema).forEach(view -> relations.add(
                    new QualifiedRelation(scope.catalog(), schema, view.getName(), "view")
            ));
        }

        var normalizedPrefix = StringUtils.defaultString(prefix).trim().toLowerCase(Locale.ROOT);
        var filtered = relations.stream()
                .filter(relation -> normalizedPrefix.isEmpty()
                        || relation.name().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        || (relation.schema() + "." + relation.name()).toLowerCase(Locale.ROOT)
                        .startsWith(normalizedPrefix))
                .sorted(Comparator.comparing(QualifiedRelation::schema, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(QualifiedRelation::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(QualifiedRelation::kind))
                .toList();

        var boundedLimit = limit == null
                ? DEFAULT_RELATION_LIMIT
                : Math.max(1, Math.min(limit, MAX_RELATION_LIMIT));
        return new RelationMetadataPage(
                List.copyOf(filtered.subList(0, Math.min(filtered.size(), boundedLimit))),
                filtered.size() > boundedLimit
        );
    }

    public List<RelationColumnsMetadata> listColumns(
            ResourceNodeSnapshot node,
            String context,
            List<QualifiedRelation> requestedRelations
    ) throws SQLException {
        if (requestedRelations == null || requestedRelations.isEmpty()) {
            return List.of();
        }
        if (requestedRelations.size() > MAX_COLUMN_RELATIONS) {
            throw new IllegalArgumentException("Too many relations in one metadata request");
        }

        var scope = this.resolveScope(node, context);
        var availableSchemas = this.resolveSchemas(null);
        var canonicalSchemas = availableSchemas.stream()
                .collect(Collectors.toMap(Function.identity(), Function.identity(), (left, right) -> left));
        var relationsBySchema = new LinkedHashMap<String, Map<RelationKey, QualifiedRelation>>();
        var canonicalRequests = new LinkedHashMap<RelationKey, QualifiedRelation>();

        for (var requested : requestedRelations) {
            this.validateRequestedRelation(requested, scope.catalog());
            var requestedSchema = StringUtils.defaultIfBlank(requested.schema(), scope.schema());
            var canonicalSchema = canonicalSchemas.get(requestedSchema);
            if (canonicalSchema == null) {
                throw new IllegalArgumentException("Unknown relation schema");
            }

            var availableRelations = relationsBySchema.get(canonicalSchema);
            if (availableRelations == null) {
                availableRelations = this.loadRelationsByKey(scope.catalog(), canonicalSchema);
                relationsBySchema.put(canonicalSchema, availableRelations);
            }
            var key = new RelationKey(canonicalSchema, requested.name(), requested.kind());
            var canonical = availableRelations.get(key);
            if (canonical == null) {
                throw new IllegalArgumentException("Unknown relation");
            }
            canonicalRequests.putIfAbsent(key, canonical);
        }

        var result = new ArrayList<RelationColumnsMetadata>();
        for (var entry : canonicalRequests.entrySet()) {
            var relation = entry.getValue();
            var columns = resourceBrowser.getFields(relation.schema(), relation.name()).stream()
                    .map(field -> new SqlColumnMetadata(field.getName(), field.getType(), field.isNullable()))
                    .toList();
            result.add(new RelationColumnsMetadata(relation, columns));
        }
        return List.copyOf(result);
    }

    private MetadataScope resolveScope(ResourceNodeSnapshot node, String context) throws SQLException {
        if (node == null) {
            throw new IllegalArgumentException("Invalid metadata context");
        }

        var contextKey = connectionManager.getContextKey();
        var databaseContextKey = connectionManager.getDatabaseContextKey();
        var currentContext = StringUtils.defaultString(context).trim();
        var catalog = node.database();

        if (StringUtils.equals(contextKey, databaseContextKey) && StringUtils.isNotBlank(currentContext)) {
            var allowedContexts = connectionManager.getSqlActuator().getSchemas();
            if (!allowedContexts.contains(currentContext)) {
                throw new IllegalArgumentException("Unknown database metadata context");
            }
            catalog = currentContext;
        }
        if (StringUtils.isNotBlank(catalog)) {
            SqlIdentifierUtils.validateDatabaseName(catalog);
            connectionManager.setDatabaseContext(catalog);
        }

        String schema = null;
        if (StringUtils.equals(contextKey, "schema")) {
            schema = StringUtils.defaultIfBlank(currentContext, node.schema());
            if (StringUtils.isNotBlank(catalog) && schema.startsWith(catalog + ".")) {
                schema = schema.substring(catalog.length() + 1);
            }
        } else if (StringUtils.equals(node.database(), catalog)) {
            schema = node.schema();
        }
        return new MetadataScope(catalog, schema);
    }

    private List<String> resolveSchemas(String requestedSchema) throws SQLException {
        var schemas = resourceBrowser.getSchemas().stream().map(schema -> schema.getName()).toList();
        if (StringUtils.isBlank(requestedSchema)) {
            return schemas;
        }
        return schemas.stream()
                .filter(schema -> schema.equals(requestedSchema))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("Unknown metadata schema"));
    }

    private Map<RelationKey, QualifiedRelation> loadRelationsByKey(String catalog, String schema) throws SQLException {
        var result = new LinkedHashMap<RelationKey, QualifiedRelation>();
        resourceBrowser.getTables(schema).forEach(table -> {
            var relation = new QualifiedRelation(catalog, schema, table.getName(), "table");
            result.put(new RelationKey(schema, relation.name(), relation.kind()), relation);
        });
        resourceBrowser.getViews(schema).forEach(view -> {
            var relation = new QualifiedRelation(catalog, schema, view.getName(), "view");
            result.put(new RelationKey(schema, relation.name(), relation.kind()), relation);
        });
        return result;
    }

    private void validateRequestedRelation(QualifiedRelation relation, String catalog) {
        if (relation == null || StringUtils.isBlank(relation.name()) || !RELATION_KINDS.contains(relation.kind())) {
            throw new IllegalArgumentException("Invalid relation metadata request");
        }
        if (StringUtils.isNotBlank(relation.catalog()) && StringUtils.isNotBlank(catalog)
                && !relation.catalog().equals(catalog)) {
            throw new IllegalArgumentException("Relation catalog does not match the active context");
        }
    }

    private record MetadataScope(String catalog, String schema) {
    }

    private record RelationKey(String schema, String name, String kind) {
    }
}
