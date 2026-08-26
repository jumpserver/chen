package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.SchemaMetadata;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.MetadataColumnsRequest;
import org.jumpserver.chen.web.entity.MetadataColumnsResponse;
import org.jumpserver.chen.web.entity.MetadataRelationsRequest;
import org.jumpserver.chen.web.entity.QualifiedRelation;
import org.jumpserver.chen.web.entity.RelationColumnsMetadata;
import org.jumpserver.chen.web.entity.RelationMetadataPage;
import org.jumpserver.chen.web.entity.SqlColumnMetadata;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SqlMetadataService {
    private static final Set<String> QUERY_NODE_TYPES = Set.of("datasource", "database", "schema", "table");
    private static final Set<String> RELATION_KIND_STRINGS = Set.of("table", "view");
    private static final Set<RelationKind> COMPLETION_KINDS = Set.of(RelationKind.TABLE, RelationKind.VIEW);
    private static final int DEFAULT_RELATION_LIMIT = 100;
    private static final int MAX_RELATION_LIMIT = 200;
    private static final int MAX_COLUMN_RELATIONS = 20;

    public RelationMetadataPage listRelations(MetadataRelationsRequest request) {
        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return new RelationMetadataPage(List.of(), false);
        }

        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveNode(browser, request == null ? null : request.getNodeKey());
        try {
            var scope = browser.resolveScope(node, request == null ? null : request.getContext());
            var catalog = datasource.getMetadataCatalog();
            var relations = catalog.listRelations(scope, COMPLETION_KINDS);
            var normalizedPrefix = StringUtils.defaultString(request.getPrefix()).trim().toLowerCase(Locale.ROOT);
            var filtered = relations.stream()
                    .filter(relation -> normalizedPrefix.isEmpty()
                            || relation.ref().name().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                            || (relation.ref().schema() + "." + relation.ref().name())
                            .toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                    .sorted(Comparator.comparing((RelationMetadata relation) -> relation.ref().schema(), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(relation -> relation.ref().name(), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(relation -> relation.ref().kind().code()))
                    .toList();
            var boundedLimit = request.getLimit() == null
                    ? DEFAULT_RELATION_LIMIT
                    : Math.max(1, Math.min(request.getLimit(), MAX_RELATION_LIMIT));
            var truncated = filtered.size() > boundedLimit;
            var items = filtered.stream().limit(boundedLimit)
                    .map(relation -> new QualifiedRelation(
                            relation.ref().catalog(), relation.ref().schema(), relation.ref().name(), relation.ref().kind().code()))
                    .toList();
            return new RelationMetadataPage(items, truncated);
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load SQL relation metadata", e);
        }
    }

    public MetadataColumnsResponse listColumns(MetadataColumnsRequest request) {
        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return new MetadataColumnsResponse(List.of());
        }

        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveNode(browser, request == null ? null : request.getNodeKey());
        try {
            var scope = browser.resolveScope(node, request == null ? null : request.getContext());
            var catalog = datasource.getMetadataCatalog();
            var refs = this.resolveRelations(catalog, scope, request.getRelations());
            var columns = catalog.listColumns(refs);
            var columnsByRef = new LinkedHashMap<ObjectRef, List<SqlColumnMetadata>>();
            for (var column : columns) {
                columnsByRef.computeIfAbsent(column.owner(), ignored -> new ArrayList<>())
                        .add(new SqlColumnMetadata(column.name(), column.nativeType(), column.nullable()));
            }
            var items = refs.stream()
                    .map(ref -> new RelationColumnsMetadata(
                            new QualifiedRelation(ref.catalog(), ref.schema(), ref.name(), ref.kind().code()),
                            columnsByRef.getOrDefault(ref, List.of())))
                    .toList();
            return new MetadataColumnsResponse(items);
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load SQL column metadata", e);
        }
    }

    private List<ObjectRef> resolveRelations(
            MetadataCatalog catalog, RelationScope scope, List<QualifiedRelation> requestedRelations
    ) throws SQLException {
        if (requestedRelations == null || requestedRelations.isEmpty()) {
            return List.of();
        }
        if (requestedRelations.size() > MAX_COLUMN_RELATIONS) {
            throw new IllegalArgumentException("Too many relations in one metadata request");
        }

        var availableSchemas = catalog.listSchemas(scope.catalog()).stream().map(SchemaMetadata::name).toList();
        var relationsBySchema = new LinkedHashMap<String, Map<RelationKey, ObjectRef>>();
        var canonicalRequests = new LinkedHashMap<ObjectRef, ObjectRef>();
        for (var requested : requestedRelations) {
            this.validateRequestedRelation(requested, scope.catalog());
            var requestedSchema = StringUtils.defaultIfBlank(requested.schema(), scope.schema());
            var canonicalSchema = this.resolveCanonicalIdentifier(availableSchemas, requestedSchema, "Unknown relation schema");

            var availableRelations = relationsBySchema.get(canonicalSchema);
            if (availableRelations == null) {
                availableRelations = this.loadRelationsByKey(catalog, scope.catalog(), canonicalSchema);
                relationsBySchema.put(canonicalSchema, availableRelations);
            }

            var kind = RelationKind.fromCode(requested.kind());
            var key = new RelationKey(canonicalSchema, requested.name(), kind);
            var canonical = availableRelations.get(key);
            if (canonical == null) {
                var canonicalName = this.resolveCanonicalIdentifier(
                        availableRelations.values().stream().filter(ref -> ref.kind() == kind).map(ObjectRef::name).toList(),
                        requested.name(),
                        "Unknown relation"
                );
                canonical = availableRelations.get(new RelationKey(canonicalSchema, canonicalName, kind));
            }
            canonicalRequests.putIfAbsent(canonical, canonical);
        }
        return List.copyOf(canonicalRequests.keySet());
    }

    private Map<RelationKey, ObjectRef> loadRelationsByKey(MetadataCatalog catalog, String catalogName, String schema)
            throws SQLException {
        var result = new LinkedHashMap<RelationKey, ObjectRef>();
        for (var relation : catalog.listRelations(new RelationScope(catalogName, schema), COMPLETION_KINDS)) {
            var ref = relation.ref();
            result.put(new RelationKey(schema, ref.name(), ref.kind()), ref);
        }
        return result;
    }

    private void validateRequestedRelation(QualifiedRelation relation, String catalog) {
        if (relation == null || StringUtils.isBlank(relation.name()) || !RELATION_KIND_STRINGS.contains(relation.kind())) {
            throw new IllegalArgumentException("Invalid relation metadata request");
        }
        if (StringUtils.isNotBlank(relation.catalog()) && StringUtils.isNotBlank(catalog)
                && !relation.catalog().equals(catalog)) {
            throw new IllegalArgumentException("Relation catalog does not match the active context");
        }
    }

    private String resolveCanonicalIdentifier(Collection<String> candidates, String requested, String unknownMessage) {
        if (StringUtils.isBlank(requested)) {
            throw new IllegalArgumentException(unknownMessage);
        }
        var exactMatches = candidates.stream().filter(requested::equals).distinct().toList();
        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        var caseRule = this.identifierCaseRule();
        if (caseRule == IdentifierCaseRule.LOWER || caseRule == IdentifierCaseRule.UPPER) {
            var normalizedRequested = caseRule.normalize(requested);
            var normalizedMatches = candidates.stream()
                    .filter(candidate -> candidate.equals(caseRule.normalize(candidate)))
                    .filter(candidate -> candidate.equals(normalizedRequested))
                    .distinct()
                    .toList();
            if (normalizedMatches.size() == 1) {
                return normalizedMatches.get(0);
            }
            throw new IllegalArgumentException(unknownMessage);
        }
        if (caseRule == IdentifierCaseRule.INSENSITIVE) {
            var insensitiveMatches = candidates.stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(requested))
                    .distinct()
                    .toList();
            if (insensitiveMatches.size() == 1) {
                return insensitiveMatches.get(0);
            }
        }
        throw new IllegalArgumentException(unknownMessage);
    }

    private IdentifierCaseRule identifierCaseRule() {
        var connectInfo = SessionManager.getCurrentSession().getDatasource().getConnectInfo();
        var dbType = connectInfo == null ? "" : StringUtils.defaultString(connectInfo.getDbType());
        return switch (dbType.toLowerCase(Locale.ROOT)) {
            case "postgresql" -> IdentifierCaseRule.LOWER;
            case "oracle", "db2", "dm", "dameng" -> IdentifierCaseRule.UPPER;
            case "mysql", "mariadb", "sqlserver" -> IdentifierCaseRule.INSENSITIVE;
            default -> IdentifierCaseRule.EXACT;
        };
    }

    private ResourceNodeSnapshot resolveNode(ResourceBrowser browser, String nodeKey) {
        if (StringUtils.isBlank(nodeKey)) {
            throw new ChenException("Invalid metadata context");
        }
        var node = browser.getIndexedNode(nodeKey);
        if (node == null || !QUERY_NODE_TYPES.contains(node.type())) {
            throw new ChenException("Invalid metadata context");
        }
        return node;
    }

    private record RelationKey(String schema, String name, RelationKind kind) {
    }

    private enum IdentifierCaseRule {
        LOWER,
        UPPER,
        INSENSITIVE,
        EXACT;

        private String normalize(String identifier) {
            return this == UPPER ? identifier.toUpperCase(Locale.ROOT) : identifier.toLowerCase(Locale.ROOT);
        }
    }
}
