package org.jumpserver.chen.web.entity;

import java.util.List;
import java.util.Set;

public record SchemaOverviewMetadata(
        String catalog,
        String schema,
        Capabilities capabilities,
        Set<String> loadedSections,
        List<TableMetadata> tables,
        List<ViewMetadata> views,
        List<StatisticMetadata> statistics,
        List<IndexMetadata> indexes,
        List<DiagramTableMetadata> diagram,
        String ddl
) {
    public record Capabilities(
            boolean tableRows,
            boolean tableSize,
            boolean tableEngine,
            boolean tableCharacterSet,
            boolean tableCollation,
            boolean tableComment,
            boolean viewComment,
            boolean statistics,
            boolean indexes,
            boolean ddl,
            boolean diagram,
            boolean diagramRelationships
    ) {
    }

    public record TableMetadata(
            String name,
            String schema,
            Long estimatedRows,
            Long totalSizeBytes,
            String engine,
            String characterSet,
            String collation,
            String comment
    ) {
    }

    public record ViewMetadata(
            String name,
            String schema,
            String type,
            String comment
    ) {
    }

    public record StatisticMetadata(
            String schema,
            String table,
            Long estimatedRows,
            Long totalSizeBytes
    ) {
    }

    public record IndexMetadata(
            String name,
            String schema,
            String table,
            List<String> columns,
            Boolean unique,
            String method,
            String definition
    ) {
    }

    public record DiagramTableMetadata(
            String schema,
            String name,
            List<DiagramColumnMetadata> columns,
            List<String> primaryKey,
            List<DiagramForeignKeyMetadata> foreignKeys
    ) {
    }

    public record DiagramColumnMetadata(
            String name,
            int ordinal,
            String nativeType,
            boolean nullable
    ) {
    }

    public record DiagramForeignKeyMetadata(
            String name,
            List<String> columns,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns
    ) {
    }
}
