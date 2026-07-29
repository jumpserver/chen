package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.SavepointRollbackFailedException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class UserManagedTransactionBoundary implements TransactionBoundary {
    private static final AtomicLong SAVEPOINT_SEQUENCE = new AtomicLong();

    private final SavepointController savepointController;

    UserManagedTransactionBoundary(SavepointController savepointController) {
        this.savepointController = savepointController;
    }

    @Override
    public <T> T execute(Connection connection, TableChangesPlan plan, TransactionWork<T> work) throws SQLException {
        String savepointName = "chen_dataview_" + SAVEPOINT_SEQUENCE.incrementAndGet();
        this.savepointController.create(connection, savepointName);
        try {
            T result = work.execute();
            this.savepointController.release(connection, savepointName);
            return result;
        } catch (SQLException | RuntimeException e) {
            rollbackToSavepoint(connection, plan, savepointName, e);
            throw e;
        }
    }

    private void rollbackToSavepoint(
            Connection connection,
            TableChangesPlan plan,
            String savepointName,
            Throwable primaryException
    ) throws SavepointRollbackFailedException {
        try {
            this.savepointController.rollbackTo(connection, savepointName);
        } catch (SQLException rollbackException) {
            log.warn(
                    "rollback DataView savepoint failed, table={}.{}, savepoint={}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    savepointName,
                    rollbackException.getSQLState(),
                    rollbackException.getErrorCode(),
                    rollbackException.getMessage(),
                    rollbackException
            );
            SavepointRollbackFailedException failure =
                    new SavepointRollbackFailedException(rollbackException, primaryException);
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeException) {
                failure.addSuppressed(closeException);
            }
            throw failure;
        }
    }
}
