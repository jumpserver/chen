package org.jumpserver.chen.framework.console.transaction;

public enum QueryTransactionState {
    AUTO_COMMIT,
    MANUAL_COMMIT_IDLE,
    TRANSACTION_ACTIVE,
    TRANSACTION_FAILED,
    UNKNOWN
}
