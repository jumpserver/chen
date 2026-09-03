package org.jumpserver.chen.web.entity;

import java.util.List;
import java.util.Set;

public record TableMetadata(
        String catalog,
        String schema,
        String name,
        String kind,
        Capabilities capabilities,
        Set<String> loadedSections,
        List<Column> columns,
        PrimaryKey primaryKey,
        List<ForeignKey> foreignKeys,
        List<Index> indexes,
        List<Constraint> constraints,
        String ddl
) {
    public record Capabilities(
            boolean columns,
            boolean primaryKey,
            boolean foreignKeys,
            boolean indexes,
            boolean constraints,
            boolean ddl
    ) {
    }

    public record Column(
            String name,
            int ordinal,
            String nativeType,
            int jdbcType,
            Integer size,
            Integer scale,
            boolean nullable,
            String defaultValue,
            String comment
    ) {
    }

    public record PrimaryKey(String name, List<String> columns) {
    }

    public record ForeignKey(
            String name,
            List<String> columns,
            String referencedCatalog,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns
    ) {
    }

    public record Index(
            String name,
            boolean unique,
            String method,
            List<IndexPart> parts,
            String definition
    ) {
    }

    public record IndexPart(
            int ordinal,
            String columnName,
            String expression,
            String sortOrder,
            boolean included
    ) {
    }

    public record Constraint(
            String name,
            String type,
            List<String> columns,
            String referencedCatalog,
            String referencedSchema,
            String referencedTable,
            List<String> referencedColumns,
            String definition
    ) {
    }
}
