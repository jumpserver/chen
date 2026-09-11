package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.ConstraintMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ForeignKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.metadata.MetadataQueryAuditContext;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.PrimaryKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.TableMetadata;
import org.jumpserver.chen.web.entity.TableMetadataRequest;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TableMetadataService {
    private static final Set<String> DEFAULT_SECTIONS = Set.of("columns", "primaryKey");
    private static final Set<String> AUDITED_PROPERTY_SECTIONS = Set.of(
            "foreignKeys", "indexes", "constraints", "ddl"
    );

    public TableMetadata getTableMetadata(TableMetadataRequest request) {
        var session = SessionManager.getCurrentSession();
        var datasource = session.getDatasource();
        var node = resolveRelationNode(datasource.getResourceBrowser(), request == null ? null : request.nodeKey());
        var ref = new ObjectRef(
                node.database(), node.schema(), node.table(), relationKindForNodeType(node.type())
        );
        try {
            var sections = normalizeSections(request == null ? null : request.sections());
            var force = request != null && request.force();
            if (!shouldAuditPropertyQuery(sections, force)) {
                return load(datasource.getMetadataCatalog(), ref, sections, force);
            }
            try (var ignored = MetadataQueryAuditContext.open()) {
                return load(datasource.getMetadataCatalog(), ref, sections, force);
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load table metadata", e);
        }
    }

    static boolean shouldAuditPropertyQuery(Set<String> sections, boolean force) {
        return !force && sections.stream().anyMatch(AUDITED_PROPERTY_SECTIONS::contains);
    }

    TableMetadata load(MetadataCatalog catalog, ObjectRef ref, Set<String> sections, boolean force)
            throws SQLException {
        if (force) {
            catalog.invalidate(ref);
        }
        return load(catalog, ref, sections);
    }

    TableMetadata load(MetadataCatalog catalog, ObjectRef ref, Set<String> sections) throws SQLException {
        var columns = sections.contains("columns")
                ? catalog.listColumns(List.of(ref)).stream().map(column -> new TableMetadata.Column(
                        column.name(), column.ordinal(), column.nativeType(), column.jdbcType(),
                        column.size(), column.scale(), column.nullable(), column.defaultValue(), column.comment()
                )).toList()
                : List.<TableMetadata.Column>of();

        TableMetadata.PrimaryKey primaryKey = null;
        if (sections.contains("primaryKey")) {
            primaryKey = catalog.listPrimaryKeys(List.of(ref)).stream().findFirst()
                    .map(this::toPrimaryKey).orElse(null);
        }

        var foreignKeys = sections.contains("foreignKeys")
                ? catalog.listForeignKeys(List.of(ref)).stream().map(this::toForeignKey).toList()
                : List.<TableMetadata.ForeignKey>of();
        var indexes = sections.contains("indexes")
                ? catalog.listIndexes(List.of(ref)).stream().map(this::toIndex).toList()
                : List.<TableMetadata.Index>of();
        var constraints = sections.contains("constraints")
                ? catalog.listConstraints(List.of(ref)).stream().map(this::toConstraint).toList()
                : List.<TableMetadata.Constraint>of();
        var ddl = sections.contains("ddl") ? catalog.getTableDefinition(ref) : null;

        return new TableMetadata(
                ref.catalog(), ref.schema(), ref.name(), ref.kind().code(),
                toCapabilities(catalog.capabilities()), sections,
                columns, primaryKey, foreignKeys, indexes, constraints, ddl
        );
    }

    private TableMetadata.PrimaryKey toPrimaryKey(PrimaryKeyMetadata primaryKey) {
        return new TableMetadata.PrimaryKey(primaryKey.name(), primaryKey.columns());
    }

    private TableMetadata.ForeignKey toForeignKey(ForeignKeyMetadata foreignKey) {
        var referenced = foreignKey.referenced();
        return new TableMetadata.ForeignKey(
                foreignKey.name(), foreignKey.columns(),
                referenced == null ? null : referenced.catalog(),
                referenced == null ? null : referenced.schema(),
                referenced == null ? null : referenced.name(),
                foreignKey.referencedColumns()
        );
    }

    private TableMetadata.Index toIndex(IndexMetadata index) {
        return new TableMetadata.Index(
                index.name(), index.unique(), index.method(),
                index.parts().stream().map(part -> new TableMetadata.IndexPart(
                        part.ordinal(), part.columnName(), part.expression(), part.sortOrder(), part.included()
                )).toList(),
                index.definition()
        );
    }

    private TableMetadata.Constraint toConstraint(ConstraintMetadata constraint) {
        var referenced = constraint.referenced();
        return new TableMetadata.Constraint(
                constraint.name(), constraint.type().code(), constraint.columns(),
                referenced == null ? null : referenced.catalog(),
                referenced == null ? null : referenced.schema(),
                referenced == null ? null : referenced.name(),
                constraint.referencedColumns(), constraint.definition()
        );
    }

    private TableMetadata.Capabilities toCapabilities(MetadataCapabilities capabilities) {
        return new TableMetadata.Capabilities(
                capabilities.columns(), capabilities.primaryKeys(), capabilities.foreignKeys(),
                capabilities.indexes(), capabilities.constraints(), capabilities.tableDefinitions()
        );
    }

    static Set<String> normalizeSections(Set<String> requested) {
        var source = requested == null || requested.isEmpty() ? DEFAULT_SECTIONS : requested;
        var normalized = new LinkedHashSet<String>();
        for (var section : source) {
            if (section == null || section.isBlank()) {
                continue;
            }
            var compact = section.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            normalized.add(switch (compact) {
                case "columns" -> "columns";
                case "primarykey", "pk" -> "primaryKey";
                case "foreignkeys", "foreignkey", "fk" -> "foreignKeys";
                case "indexes", "indices" -> "indexes";
                case "constraints" -> "constraints";
                case "ddl" -> "ddl";
                default -> throw new IllegalArgumentException("Unknown table metadata section: " + section);
            });
        }
        return Set.copyOf(normalized);
    }

    static RelationKind relationKindForNodeType(String type) {
        if ("view".equals(type)) {
            return RelationKind.VIEW;
        }
        if ("table".equals(type)) {
            return RelationKind.TABLE;
        }
        throw new ChenException("Invalid table metadata context");
    }

    ResourceNodeSnapshot resolveRelationNode(ResourceBrowser browser, String nodeKey) {
        if (StringUtils.isBlank(nodeKey)) {
            throw new ChenException("Invalid table metadata context");
        }
        var node = browser.getIndexedNode(nodeKey);
        if (node == null || StringUtils.isBlank(node.table())) {
            throw new ChenException("Invalid table metadata context");
        }
        relationKindForNodeType(node.type());
        return node;
    }
}
