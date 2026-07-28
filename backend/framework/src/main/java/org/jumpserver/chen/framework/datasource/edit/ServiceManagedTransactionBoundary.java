package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;

import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
final class ServiceManagedTransactionBoundary implements TransactionBoundary {
    @Override
    public <T> T execute(Connection connection, TableChangesPlan plan, TransactionWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new SQLException("SERVICE_MANAGED transaction requires autoCommit=true");
        }

        Throwable primaryException = null;
        boolean restoreAutoCommit = true;
        try {
            connection.setAutoCommit(false);
            T result = work.execute();
            this.commit(connection, plan);
            return result;
        } catch (SQLException e) {
            primaryException = e;
            log.warn(
                    "save changes transaction failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            try {
                this.rollback(connection, plan, e);
            } catch (RollbackFailedException rollbackFailedException) {
                restoreAutoCommit = false;
                throw rollbackFailedException;
            }
            throw e;
        } catch (RuntimeException e) {
            primaryException = e;
            log.warn(
                    "save changes transaction failed, table={}.{}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    e.getMessage(),
                    e
            );
            try {
                this.rollback(connection, plan, e);
            } catch (RollbackFailedException rollbackFailedException) {
                restoreAutoCommit = false;
                throw rollbackFailedException;
            }
            throw e;
        } finally {
            if (restoreAutoCommit) {
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
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeException) {
                failure.addSuppressed(closeException);
            }
            throw failure;
        }
    }

}
