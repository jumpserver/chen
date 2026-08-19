package org.jumpserver.chen.framework.datasource.metadata;

/**
 * One column or expression participating in an index.
 * Exactly one of {@code columnName} / {@code expression} is non-null.
 */
public record IndexPart(
        int ordinal,
        String columnName,
        String expression,
        String sortOrder,
        boolean included
) {
}
