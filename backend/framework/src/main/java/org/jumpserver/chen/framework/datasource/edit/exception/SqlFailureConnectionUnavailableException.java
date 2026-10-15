package org.jumpserver.chen.framework.datasource.edit.exception;

import java.sql.SQLException;

/**
 * The database rejected a SQL statement and the save outcome is known, but transaction cleanup
 * discovered that the connection can no longer be used. The caller must preserve the SQL error
 * while replacing the connection.
 */
public class SqlFailureConnectionUnavailableException extends SQLException {
    private final SQLException sqlFailure;

    public SqlFailureConnectionUnavailableException(
            SQLException sqlFailure,
            SQLException cleanupFailure
    ) {
        super(sqlFailure.getMessage(), sqlFailure.getSQLState(), sqlFailure.getErrorCode(), sqlFailure);
        this.sqlFailure = sqlFailure;
        this.addSuppressed(cleanupFailure);
    }

    public SQLException getSqlFailure() {
        return this.sqlFailure;
    }
}
