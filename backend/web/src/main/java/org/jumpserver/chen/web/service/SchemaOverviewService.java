package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.metadata.ObjectStatistics;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.SchemaOverviewMetadata;
import org.jumpserver.chen.web.entity.SchemaOverviewRequest;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SchemaOverviewService {
    private static final Set<RelationKind> TABLE_KINDS = Set.of(RelationKind.TABLE);
    private static final Set<RelationKind> VIEW_KINDS = Set.of(RelationKind.VIEW, RelationKind.MATERIALIZED_VIEW);
    private static final Set<String> DEFAULT_SECTIONS = Set.of("tables", "views");
    private static final Set<String> ALLOWED_SECTIONS = Set.of(
            "tables", "views", "statistics", "indexes", "ddl", "diagram"
    );

    public SchemaOverviewMetadata getSchemaOverview(SchemaOverviewRequest request) {
        var session = SessionManager.getCurrentSession();
        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveSchemaNode(browser, request == null ? null : request.nodeKey());
        try {
            var scope = new RelationScope(node.database(), node.schema());
            var sections = normalizeSections(request == null ? null : request.sections());
            return this.load(
                    datasource.getMetadataCatalog(), scope, sections,
                    request != null && request.force()
            );
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load schema overview metadata", e);
        }
    }

    SchemaOverviewMetadata load(
            MetadataCatalog catalog, RelationScope scope, Set<String> sections, boolean force
    ) throws SQLException {
        if (force) {
            catalog.invalidate(scope);
        }
        return this.load(catalog, scope, sections);
    }

    SchemaOverviewMetadata load(MetadataCatalog catalog, RelationScope scope, Set<String> sections)
            throws SQLException {
        var statistics = sections.contains("statistics") ? catalog.listStatistics(scope) : List.<ObjectStatistics>of();
        var statsByName = new HashMap<String, ObjectStatistics>();
        for (var statistic : statistics) {
            statsByName.put(statistic.ref().name(), statistic);
        }
        return new SchemaOverviewMetadata(
                scope.catalog(),
                scope.schema(),
                this.toCapabilities(catalog.capabilities()),
                sections,
                sections.contains("tables") ? this.loadTables(catalog, scope, statsByName) : List.of(),
                sections.contains("views") ? this.loadViews(catalog, scope) : List.of(),
                statistics.stream().map(statistic -> new SchemaOverviewMetadata.StatisticMetadata(
                        statistic.ref().schema(), statistic.ref().name(),
                        statistic.estimatedRows(), statistic.totalSizeBytes()
                )).toList(),
                sections.contains("indexes") ? this.loadIndexes(catalog, scope) : List.of(),
                sections.contains("diagram") ? this.loadDiagram(catalog, scope) : List.of(),
                sections.contains("ddl") ? catalog.getSchemaDefinition(scope) : null
        );
    }

    private List<SchemaOverviewMetadata.DiagramTableMetadata> loadDiagram(
            MetadataCatalog catalog,
            RelationScope scope
    ) throws SQLException {
        var refs = catalog.listRelations(scope, TABLE_KINDS).stream().map(relation -> relation.ref()).toList();
        var columnsByOwner = catalog.listColumns(refs).stream().collect(Collectors.groupingBy(
                column -> column.owner(), LinkedHashMap::new, Collectors.toList()
        ));
        var primaryKeysByOwner = catalog.listPrimaryKeys(refs).stream().collect(Collectors.toMap(
                primaryKey -> primaryKey.owner(), primaryKey -> primaryKey,
                (first, ignored) -> first, LinkedHashMap::new
        ));
        var foreignKeysByOwner = catalog.listForeignKeys(refs).stream().collect(Collectors.groupingBy(
                foreignKey -> foreignKey.owner(), LinkedHashMap::new, Collectors.toList()
        ));

        return refs.stream().map(ref -> {
            var primaryKey = primaryKeysByOwner.get(ref);
            return new SchemaOverviewMetadata.DiagramTableMetadata(
                    ref.schema(), ref.name(),
                    columnsByOwner.getOrDefault(ref, List.of()).stream().map(column ->
                            new SchemaOverviewMetadata.DiagramColumnMetadata(
                                    column.name(), column.ordinal(), column.nativeType(), column.nullable()
                            )
                    ).toList(),
                    primaryKey == null ? List.of() : primaryKey.columns(),
                    foreignKeysByOwner.getOrDefault(ref, List.of()).stream().map(foreignKey -> {
                        var referenced = foreignKey.referenced();
                        return new SchemaOverviewMetadata.DiagramForeignKeyMetadata(
                                foreignKey.name(), foreignKey.columns(),
                                referenced == null ? null : referenced.schema(),
                                referenced == null ? null : referenced.name(),
                                foreignKey.referencedColumns()
                        );
                    }).toList()
            );
        }).toList();
    }

    private List<SchemaOverviewMetadata.TableMetadata> loadTables(
            MetadataCatalog catalog,
            RelationScope scope,
            Map<String, ObjectStatistics> statsByName
    )
            throws SQLException {
        var relations = catalog.listRelations(scope, TABLE_KINDS);
        return relations.stream().map(relation -> {
            var stat = statsByName.get(relation.ref().name());
            return new SchemaOverviewMetadata.TableMetadata(
                    relation.ref().name(),
                    relation.ref().schema(),
                    stat == null ? null : stat.estimatedRows(),
                    stat == null ? null : stat.totalSizeBytes(),
                    relation.engine(),
                    relation.characterSet(),
                    relation.collation(),
                    relation.comment()
            );
        }).toList();
    }

    private List<SchemaOverviewMetadata.ViewMetadata> loadViews(MetadataCatalog catalog, RelationScope scope)
            throws SQLException {
        return catalog.listRelations(scope, VIEW_KINDS).stream()
                .map(relation -> new SchemaOverviewMetadata.ViewMetadata(
                        relation.ref().name(),
                        relation.ref().schema(),
                        this.viewType(relation.ref().kind()),
                        relation.comment()
                ))
                .toList();
    }

    private List<SchemaOverviewMetadata.IndexMetadata> loadIndexes(MetadataCatalog catalog, RelationScope scope)
            throws SQLException {
        return catalog.listIndexes(scope).stream()
                .map(this::toIndexMetadata)
                .toList();
    }

    SchemaOverviewMetadata.IndexMetadata toIndexMetadata(
            org.jumpserver.chen.framework.datasource.metadata.IndexMetadata index
    ) {
        return new SchemaOverviewMetadata.IndexMetadata(
                index.name(),
                index.owner().schema(),
                index.owner().name(),
                index.parts().stream()
                        .map(part -> part.columnName() != null ? part.columnName() : part.expression())
                        .filter(Objects::nonNull)
                        .toList(),
                index.unique(),
                index.method(),
                index.definition()
        );
    }

    private SchemaOverviewMetadata.Capabilities toCapabilities(MetadataCapabilities capabilities) {
        return new SchemaOverviewMetadata.Capabilities(
                capabilities.tableRows(),
                capabilities.tableSize(),
                capabilities.tableEngine(),
                capabilities.tableCharacterSet(),
                capabilities.tableCollation(),
                capabilities.tableComment(),
                capabilities.viewComment(),
                capabilities.statistics(),
                capabilities.indexes(),
                capabilities.definitions(),
                capabilities.relations() && capabilities.columns(),
                capabilities.foreignKeys()
        );
    }

    static Set<String> normalizeSections(Set<String> requested) {
        var source = requested == null || requested.isEmpty() ? DEFAULT_SECTIONS : requested;
        var normalized = new LinkedHashSet<String>();
        for (var section : source) {
            if (section == null || section.isBlank()) {
                continue;
            }
            var value = section.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (!ALLOWED_SECTIONS.contains(value)) {
                throw new IllegalArgumentException("Unknown schema metadata section: " + section);
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private String viewType(RelationKind kind) {
        return kind == RelationKind.MATERIALIZED_VIEW ? "MATERIALIZED VIEW" : "VIEW";
    }

    private ResourceNodeSnapshot resolveSchemaNode(ResourceBrowser browser, String nodeKey) {
        if (StringUtils.isBlank(nodeKey)) {
            throw new ChenException("Invalid schema overview context");
        }
        var node = browser.getIndexedNode(nodeKey);
        if (node == null || !"schema".equals(node.type()) || StringUtils.isBlank(node.schema())) {
            throw new ChenException("Invalid schema overview context");
        }
        return node;
    }
}
