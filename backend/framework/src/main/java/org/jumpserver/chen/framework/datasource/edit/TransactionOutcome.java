package org.jumpserver.chen.framework.datasource.edit;

record TransactionOutcome<T>(
        T value,
        boolean databaseCommitted,
        boolean connectionInvalidated,
        String reason
) {
    static <T> TransactionOutcome<T> success(T value, boolean databaseCommitted) {
        return new TransactionOutcome<>(value, databaseCommitted, false, null);
    }
}
