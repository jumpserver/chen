package org.jumpserver.chen.web.entity;

import java.util.List;

public record SchemaOverviewMetadata(
        String catalog,
        String schema,
        Capabilities capabilities,
        List<TableMetadata> tables,
        List<ViewMetadata> views,
        List<IndexMetadata> indexes,
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
            boolean indexes,
            boolean ddl
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
}
