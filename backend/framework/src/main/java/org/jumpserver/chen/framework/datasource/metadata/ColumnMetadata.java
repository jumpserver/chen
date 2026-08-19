package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Canonical column metadata. {@code owner} carries the owning relation so batch
 * results can be reassociated. {@code nativeType} is the SQL type name;
 * {@code jdbcType} is the {@link java.sql.Types} code (0 when unknown).
 */
public record ColumnMetadata(
        ObjectRef owner,
        String name,
        int ordinal,
        String nativeType,
        int jdbcType,
        boolean nullable,
        String defaultValue,
        String comment
) {
}
