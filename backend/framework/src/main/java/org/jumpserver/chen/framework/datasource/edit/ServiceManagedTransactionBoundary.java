package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitOutcomeUnknownException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RolledBackConnectionUnavailableException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs a DataView save batch on a connection the service owns (autoCommit=true at entry).
 *
 * A commit failure is treated as an unknown outcome: the database may have committed despite
 * the client never receiving the acknowledgement. Rolling back would either mask a real commit
 * or fail meaninglessly, so the connection is discarded and the caller reports the uncertainty
 * rather than pretending the batch was rolled back. Failures that occur before commit (a
 * statement error, a runtime bug in binding, etc.) have committed nothing and are rolled back
 * normally.
 */
@Slf4j
final class ServiceManagedTransactionBoundary implements TransactionBoundary {
    @Override
    public <T> TransactionOutcome<T> execute(
            Connection connection,
            TableChangesPlan plan,
            TransactionWork<T> work
    ) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new SQLException("SERVICE_MANAGED transaction requires autoCommit=true");
        }

        connection.setAutoCommit(false);
        T result;
        try {
            result = work.execute();
        } catch (SQLException | RuntimeException e) {
            try {
                this.rollback(connection, plan, e);
            } catch (RollbackFailedException rollbackFailure) {
                throw rollbackFailure;
            }
            try {
                this.restoreAutoCommit(connection, plan, originalAutoCommit);
            } catch (SQLException restoreFailure) {
                RolledBackConnectionUnavailableException failure =
                        new RolledBackConnectionUnavailableException(restoreFailure, e);
                this.discardConnection(connection, failure);
                throw failure;
            }
            throw e;
        }

        try {
            this.commit(connection, plan);
        } catch (CommitOutcomeUnknownException e) {
            this.discardConnection(connection, e);
            throw e;
        }

        try {
            this.restoreAutoCommit(connection, plan, originalAutoCommit);
            return TransactionOutcome.success(result, true);
        } catch (SQLException restoreFailure) {
            this.discardConnection(connection, restoreFailure);
            return new TransactionOutcome<>(
                    result,
                    true,
                    true,
                    TableChangesSaveService.SAVE_CHANGES_COMMITTED_CONNECTION_UNAVAILABLE
            );
        }
    }

    private void commit(Connection connection, TableChangesPlan plan) throws SQLException {
        try {
            connection.commit();
        } catch (SQLException e) {
            log.error(
                    "save changes commit failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            throw new CommitOutcomeUnknownException(e);
        }
    }

    private void rollback(Connection connection, TableChangesPlan plan, Throwable primaryException)
            throws RollbackFailedException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn(
                    "rollback save changes transaction failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            RollbackFailedException failure = new RollbackFailedException(e, primaryException);
            this.discardConnection(connection, failure);
            throw failure;
        }
    }

    private void restoreAutoCommit(
            Connection connection,
            TableChangesPlan plan,
            boolean originalAutoCommit
    ) throws SQLException {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            log.warn(
                    "restore save changes connection autoCommit failed, table={}.{}, originalAutoCommit={}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    originalAutoCommit,
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            throw e;
        }
    }

    private void discardConnection(Connection connection, Throwable failure) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }
}
