package org.jumpserver.chen.framework.datasource.plan;

public final class PlanLimits {
    public static final int MAX_SQL_CHARS = 1_000_000;
    public static final int MAX_RAW_BYTES = 2_000_000;
    public static final int MAX_NODES = 5_000;
    public static final int MAX_DEPTH = 64;
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    public static final long CLEANUP_TIMEOUT_MS = 5_000L;

    private PlanLimits() {
    }
}
