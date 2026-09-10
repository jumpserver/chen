package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Narrow marker enabled by the table-property and schema-overview services when
 * provider SQL belongs to the user-facing metadata request.
 */
public final class MetadataQueryAuditContext implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private MetadataQueryAuditContext() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static MetadataQueryAuditContext open() {
        return new MetadataQueryAuditContext();
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    @Override
    public void close() {
        var depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }
}
