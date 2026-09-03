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
    /** Compatibility constructor retained for existing completion/catalog callers. */
    public ColumnMetadata(
            ObjectRef owner, String name, int ordinal, String nativeType, int jdbcType,
            boolean nullable, String defaultValue, String comment
    ) {
        this(owner, name, ordinal, nativeType, jdbcType, null, null, nullable, defaultValue, comment);
    }
}
