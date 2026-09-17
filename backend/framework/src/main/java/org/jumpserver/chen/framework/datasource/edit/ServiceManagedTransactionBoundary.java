package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitOutcomeUnknownException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RolledBackConnectionUnavailableException;

import java.sql.Connection;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

/**
 * Runs a DataView save batch on a connection the service owns (autoCommit=true at entry).
 *
 * A connection failure during commit has an unknown outcome: the database may have committed
 * despite the client never receiving the acknowledgement, so that connection is discarded.
 * A definite database-side SQL rejection during commit is different: it is rolled back and the
 * original SQL error is returned, just like a statement failure. Failures before commit are also
 * rolled back normally.
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
            this.rollbackAndRestore(connection, plan, originalAutoCommit, e);
            throw e;
        }

        try {
            this.commit(connection);
        } catch (SQLException commitFailure) {
            if (isDefiniteSqlFailure(commitFailure)) {
                log.warn(
                        "save changes commit rejected by database, table={}.{}, sqlState={}, vendorCode={}, message={}",
                        plan.getSchema(),
                        plan.getTable(),
                        commitFailure.getSQLState(),
                        commitFailure.getErrorCode(),
                        commitFailure.getMessage()
                );
                this.rollbackAndRestore(connection, plan, originalAutoCommit, commitFailure);
                throw commitFailure;
            }
            log.error(
                    "save changes commit outcome unknown, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    commitFailure.getSQLState(),
                    commitFailure.getErrorCode(),
                    commitFailure.getMessage(),
                    commitFailure
            );
            CommitOutcomeUnknownException unknown = new CommitOutcomeUnknownException(commitFailure);
            this.discardConnection(connection, unknown);
            throw unknown;
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

    private void commit(Connection connection) throws SQLException {
        connection.commit();
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

    /**
     * A commit can surface a database-side SQL rejection (for example a deferred permission or
     * read-only error) as well as a broken connection. Only the latter has an unknown outcome.
     * SQLState is the common contract used by all supported JDBC drivers; message matching is a
     * narrow fallback for drivers that omit SQLState on explicit permission errors.
     */
    private static boolean isDefiniteSqlFailure(SQLException failure) {
        boolean hasDatabaseSqlState = false;
        boolean hasPermissionDeniedMessage = false;
        for (Throwable current : failure) {
            if (current instanceof SQLException sqlException) {
                if (isConnectionFailure(sqlException)) {
                    return false;
                }
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && !sqlState.isBlank()) {
                    hasDatabaseSqlState = true;
                }
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("permission denied") ||
                    normalized.contains("access denied") ||
                    normalized.contains("command denied") ||
                    normalized.contains("insufficient privilege") ||
                    normalized.contains("not authorized") ||
                    normalized.contains("not authorised")) {
                hasPermissionDeniedMessage = true;
            }
        }
        return hasDatabaseSqlState || hasPermissionDeniedMessage;
    }

    private static boolean isConnectionFailure(SQLException failure) {
        if (failure instanceof SQLRecoverableException ||
                failure instanceof SQLTransientConnectionException ||
                failure instanceof SQLNonTransientConnectionException) {
            return true;
        }
        String sqlState = failure.getSQLState();
        return sqlState != null &&
                (sqlState.startsWith("08") ||
                        "57P01".equals(sqlState) ||
                        "57P02".equals(sqlState) ||
                        "57P03".equals(sqlState));
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
