package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Structural metadata about a relation, excluding statistics (rows/size), which
 * have a different refresh frequency and live in {@link ObjectStatistics}.
 */
public record RelationMetadata(
        ObjectRef ref,
        String comment,
        String engine,
        String characterSet,
        String collation
) {
}
