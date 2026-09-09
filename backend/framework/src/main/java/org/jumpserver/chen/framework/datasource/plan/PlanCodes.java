package org.jumpserver.chen.framework.datasource.plan;

public final class PlanCodes {
    public static final String PLAN_PARSE_FAILED = "PLAN_PARSE_FAILED";
    public static final String PLAN_LIMIT_REACHED = "PLAN_LIMIT_REACHED";
    public static final String PLAN_TABLE_MISSING = "PLAN_TABLE_MISSING";
    public static final String PLAN_TABLE_INCOMPATIBLE = "PLAN_TABLE_INCOMPATIBLE";
    public static final String PLAN_PERMISSION_DENIED = "PLAN_PERMISSION_DENIED";
    public static final String TRANSACTION_CONTEXT_UNSAFE = "TRANSACTION_CONTEXT_UNSAFE";
    public static final String UNSAFE_TO_ESTIMATE = "UNSAFE_TO_ESTIMATE";
    public static final String VERSION_NOT_VALIDATED = "VERSION_NOT_VALIDATED";
    public static final String AUXILIARY_CLEANUP_FAILED = "AUXILIARY_CLEANUP_FAILED";
    public static final String SESSION_STATE_UNSAFE = "SESSION_STATE_UNSAFE";
    public static final String SESSION_RESTORE_FAILED = "SESSION_RESTORE_FAILED";
    public static final String MISSING_REQUEST_ID = "MISSING_REQUEST_ID";
    public static final String CANCELLED = "CANCELLED";
    public static final String CONNECTION_INVALIDATED = "CONNECTION_INVALIDATED";
    public static final String SQL_ERROR = "SQL_ERROR";
    public static final String UNSUPPORTED_STATEMENT = "UNSUPPORTED_STATEMENT";
    public static final String UNSUPPORTED_DATABASE = "UNSUPPORTED_DATABASE";

    private PlanCodes() {
    }
}
