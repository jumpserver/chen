package org.jumpserver.chen.framework.datasource.plan;

public enum PlanTransactionState {
    AUTO_COMMIT,
    MANUAL_COMMIT_IDLE,
    TRANSACTION_ACTIVE,
    TRANSACTION_FAILED,
    UNKNOWN
}
