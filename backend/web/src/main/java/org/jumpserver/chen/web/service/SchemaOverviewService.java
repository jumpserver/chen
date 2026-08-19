package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.IndexPart;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class SchemaOverviewService {
    private static final Set<RelationKind> TABLE_KINDS = Set.of(RelationKind.TABLE);
    private static final Set<RelationKind> VIEW_KINDS = Set.of(RelationKind.VIEW, RelationKind.MATERIALIZED_VIEW);

    public SchemaOverviewMetadata getSchemaOverview(SchemaOverviewRequest request) {
        var session = SessionManager.getCurrentSession();
        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveSchemaNode(browser, request == null ? null : request.nodeKey());
        try {
            var scope = new RelationScope(node.database(), node.schema());
            var catalog = datasource.getMetadataCatalog();
            return new SchemaOverviewMetadata(
                    scope.catalog(),
                    scope.schema(),
                    this.toCapabilities(catalog.capabilities()),
                    this.loadTables(catalog, scope),
                    this.loadViews(catalog, scope),
                    this.loadIndexes(catalog, scope),
                    catalog.getSchemaDefinition(scope)
            );
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load schema overview metadata", e);
        }
    }

    private List<SchemaOverviewMetadata.TableMetadata> loadTables(MetadataCatalog catalog, RelationScope scope)
            throws SQLException {
        var relations = catalog.listRelations(scope, TABLE_KINDS);
        var statsByName = new HashMap<String, ObjectStatistics>();
        for (var stat : catalog.listStatistics(scope)) {
            statsByName.put(stat.ref().name(), stat);
        }
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
                .map(index -> new SchemaOverviewMetadata.IndexMetadata(
                        index.name(),
                        index.owner().schema(),
                        index.owner().name(),
                        index.parts().stream().map(IndexPart::columnName).filter(Objects::nonNull).toList(),
                        index.unique(),
                        index.method(),
                        index.definition()
                ))
                .toList();
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
                capabilities.indexes(),
                capabilities.definitions()
        );
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
