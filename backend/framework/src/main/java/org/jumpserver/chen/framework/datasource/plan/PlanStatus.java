package org.jumpserver.chen.framework.datasource.plan;

public enum PlanStatus {
    SUCCESS,
    RAW_ONLY,
    UNSUPPORTED_STATEMENT,
    UNSUPPORTED_DATABASE,
    PREREQUISITES_UNMET,
    ERROR,
    CANCELLED,
    CONNECTION_INVALIDATED
}
