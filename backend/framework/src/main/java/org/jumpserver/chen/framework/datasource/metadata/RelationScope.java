package org.jumpserver.chen.framework.datasource.metadata;

/**
 * The (catalog, schema) context for schema-scoped batch queries.
 */
public record RelationScope(String catalog, String schema) {
}
