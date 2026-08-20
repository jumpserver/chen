package org.jumpserver.chen.framework.utils;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

/**
 * Validates runtime database identifiers before they are interpolated into a
 * JDBC URL, so that URL/property metacharacters in a database name cannot
 * inject driver connection properties (e.g. PostgreSQL socketFactory/loggerFile,
 * SQLServer ";prop=val").
 */
@Slf4j
public final class SqlIdentifierUtils {

    // Characters that act as delimiters in at least one supported JDBC URL form.
    // Rejecting them keeps a database name from escaping its ${db} placeholder.
    private static final String FORBIDDEN_CHARS = "?&/:@#\\;= \t\r\n";

    private SqlIdentifierUtils() {
    }

    /**
     * Reject database names containing URL/property metacharacters or control
     * characters. Blank values are allowed; callers fall back to the configured db.
     */
    public static void validateDatabaseName(String name) throws SQLException {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || FORBIDDEN_CHARS.indexOf(c) >= 0) {
                // Do not echo the value: it may contain log-forging characters.
                log.warn("Rejected database name containing URL metacharacter");
                throw new SQLException("Invalid database identifier");
            }
        }
    }
}
