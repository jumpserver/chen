package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Estimated row count and total storage size for a relation. Estimates only;
 * never produced by {@code COUNT(*)}.
 */
public record ObjectStatistics(ObjectRef ref, Long estimatedRows, Long totalSizeBytes) {
}
