package org.jumpserver.chen.framework.datasource.entity.resource;

import org.jumpserver.chen.framework.datasource.metadata.RelationKind;

public record ResourceNodeSnapshot(
        String key,
        String type,
        String database,
        String schema,
        String table,
        String name,
        RelationKind relationKind
) {
    public ResourceNodeSnapshot(
            String key,
            String type,
            String database,
            String schema,
            String table,
            String name
    ) {
        this(key, type, database, schema, table, name, relationKindForLegacyType(type));
    }

    private static RelationKind relationKindForLegacyType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "table" -> RelationKind.TABLE;
            case "view" -> RelationKind.VIEW;
            default -> null;
        };
    }
}
