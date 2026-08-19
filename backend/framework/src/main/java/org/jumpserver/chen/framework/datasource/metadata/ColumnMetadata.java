package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Canonical column metadata. {@code owner} carries the owning relation so batch
 * results can be reassociated. {@code nativeType} is the SQL type name;
 * {@code jdbcType} is the {@link java.sql.Types} code ({@code OTHER} when unknown).
 */
public record ColumnMetadata(
        ObjectRef owner,
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
