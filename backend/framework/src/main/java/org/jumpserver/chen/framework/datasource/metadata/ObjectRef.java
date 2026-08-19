package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Canonical reference to a relation (or, by extension, a schema/catalog object).
 * JSON-compatible with the previous {@code QualifiedRelation} shape
 * (catalog is nullable, kind is the lowercase {@link RelationKind#code()}).
 */
public record ObjectRef(
        String catalog,
        String schema,
        String name,
        RelationKind kind
) {
}
