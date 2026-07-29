package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;

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
    public <T> T execute(Connection connection, TableChangesPlan plan, TransactionWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new SQLException("SERVICE_MANAGED transaction requires autoCommit=true");
        }

        connection.setAutoCommit(false);
        Throwable primaryException = null;
        boolean connectionDiscarded = false;
        try {
            T result = work.execute();
            this.commit(connection, plan);
            return result;
        } catch (CommitFailedException e) {
            primaryException = e;
            connectionDiscarded = true;
            this.discardConnection(connection, e);
            throw e;
        } catch (SQLException | RuntimeException e) {
            primaryException = e;
            try {
                this.rollback(connection, plan, e);
            } catch (RollbackFailedException rollbackFailure) {
                connectionDiscarded = true;
                throw rollbackFailure;
            }
            throw e;
        } finally {
            if (!connectionDiscarded) {
                this.restoreAutoCommit(connection, plan, originalAutoCommit, primaryException);
            }
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
            throw new CommitFailedException(e);
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
            boolean originalAutoCommit,
            Throwable primaryException
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
            if (primaryException != null) {
                primaryException.addSuppressed(e);
            } else {
                throw e;
            }
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
