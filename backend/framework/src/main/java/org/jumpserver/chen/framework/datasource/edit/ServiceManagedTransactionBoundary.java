package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitOutcomeUnknownException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RolledBackConnectionUnavailableException;
import org.jumpserver.chen.framework.datasource.edit.exception.SqlFailureConnectionUnavailableException;

import java.sql.Connection;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Runs a DataView save batch on a connection the service owns (autoCommit=true at entry).
 *
 * A commit failure is treated as an unknown outcome: the database may have committed despite
 * the client never receiving the acknowledgement, so that connection is discarded. Statement
 * failures are rolled back; when no statement completed, a cleanup failure does not make an
 * explicit database rejection uncertain.
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
        } catch (SQLException e) {
            this.handleStatementFailure(
                    connection,
                    plan,
                    originalAutoCommit,
                    work.successfulStatementCount(),
                    e
            );
            throw e;
        } catch (RuntimeException e) {
            this.rollbackAndRestore(connection, plan, originalAutoCommit, e);
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

    private void rollbackAndRestore(
            Connection connection,
            TableChangesPlan plan,
            boolean originalAutoCommit,
            Throwable primaryException
    ) throws SQLException {
        try {
            this.rollback(connection, plan, primaryException);
        } catch (RollbackFailedException rollbackFailure) {
            throw rollbackFailure;
        }
        try {
            this.restoreAutoCommit(connection, plan, originalAutoCommit);
        } catch (SQLException restoreFailure) {
            RolledBackConnectionUnavailableException failure =
                    new RolledBackConnectionUnavailableException(restoreFailure, primaryException);
            this.discardConnection(connection, failure);
            throw failure;
        }
    }

    private void handleStatementFailure(
            Connection connection,
            TableChangesPlan plan,
            boolean originalAutoCommit,
            int successfulStatementCount,
            SQLException statementFailure
    ) throws SQLException {
        try {
            this.rollback(connection, plan, statementFailure);
        } catch (RollbackFailedException rollbackFailure) {
            if (successfulStatementCount == 0 && !isConnectionFailure(statementFailure)) {
                throw knownSqlFailureWithUnavailableConnection(statementFailure, rollbackFailure);
            }
            throw rollbackFailure;
        }

        try {
            this.restoreAutoCommit(connection, plan, originalAutoCommit);
        } catch (SQLException restoreFailure) {
            SqlFailureConnectionUnavailableException failure =
                    new SqlFailureConnectionUnavailableException(statementFailure, restoreFailure);
            this.discardConnection(connection, failure);
            throw failure;
        }
    }

    private static SqlFailureConnectionUnavailableException knownSqlFailureWithUnavailableConnection(
            SQLException statementFailure,
            RollbackFailedException rollbackFailure
    ) {
        Throwable rollbackCause = rollbackFailure.getCause();
        SQLException cleanupFailure = rollbackCause instanceof SQLException sqlException
                ? sqlException
                : rollbackFailure;
        return new SqlFailureConnectionUnavailableException(statementFailure, cleanupFailure);
    }

    private static boolean isConnectionFailure(SQLException failure) {
        for (Throwable current : failure) {
            if (current instanceof SQLException sqlException) {
                if (sqlException instanceof SQLRecoverableException ||
                        sqlException instanceof SQLTransientConnectionException ||
                        sqlException instanceof SQLNonTransientConnectionException) {
                    return true;
                }
                String sqlState = sqlException.getSQLState();
                if (sqlState != null &&
                        (sqlState.startsWith("08") ||
                                "57P01".equals(sqlState) ||
                                "57P02".equals(sqlState) ||
                                "57P03".equals(sqlState))) {
                    return true;
                }
            }
        }
        return false;
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
