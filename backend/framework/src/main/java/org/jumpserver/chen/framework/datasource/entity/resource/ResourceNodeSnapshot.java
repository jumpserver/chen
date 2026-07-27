package org.jumpserver.chen.framework.datasource.entity.resource;

public record ResourceNodeSnapshot(
        String key,
        String type,
        String database,
        String schema,
        String table,
        String name
) {
}
