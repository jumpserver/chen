package org.jumpserver.chen.framework.datasource.metadata;

public record QualifiedRelation(
        String catalog,
        String schema,
        String name,
        String kind
) {
}
