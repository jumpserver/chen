package org.jumpserver.chen.framework.console;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;

import java.sql.SQLException;

/**
 * Validates that raw console input is one database command without rewriting it.
 */
final class ConsoleStatementBoundaryScanner {
    private static final String MULTIPLE_STATEMENTS_ERROR =
            "Console raw execution accepts only one top-level database command";

    private ConsoleStatementBoundaryScanner() {
    }

    static void requireSingleStatement(String sql, DbType dbType) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("Console raw execution requires a database command");
        }

        try {
            var statements = SQLUtils.parseStatements(sql, dbType);
            if (statements.size() == 1) {
                return;
            }
            if (statements.size() > 1) {
                throw new SQLException(MULTIPLE_STATEMENTS_ERROR);
            }
        } catch (RuntimeException ignored) {
            // Raw mode exists for commands the dialect parser does not understand. The conservative
            // fallback below recognizes only top-level delimiters and keeps quoted/dollar-quoted
            // procedure bodies opaque; it never changes the command sent to the JDBC driver.
        }

        requireSingleOpaqueStatement(sql);
    }

    private static void requireSingleOpaqueStatement(String sql) throws SQLException {
        int completedStatements = 0;
        boolean hasStatementContent = false;
        int blockCommentDepth = 0;
        char quote = 0;
        String dollarQuote = null;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;

            if (dollarQuote != null) {
                if (sql.startsWith(dollarQuote, index)) {
                    index += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
                continue;
            }

            if (quote != 0) {
                if (current == '\\' && index + 1 < sql.length()) {
                    index++;
                    continue;
                }
                if (current == quote) {
                    if (next == quote) {
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }

            if (blockCommentDepth > 0) {
                if (current == '/' && next == '*') {
                    blockCommentDepth++;
                    index++;
                } else if (current == '*' && next == '/') {
                    blockCommentDepth--;
                    index++;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                index = skipLineComment(sql, index + 2);
                continue;
            }
            if (current == '/' && next == '*') {
                blockCommentDepth = 1;
                index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                hasStatementContent = true;
                continue;
            }
            if (current == '$') {
                String delimiter = dollarQuoteDelimiter(sql, index);
                if (delimiter != null) {
                    dollarQuote = delimiter;
                    hasStatementContent = true;
                    index += delimiter.length() - 1;
                    continue;
                }
            }
            if (current == ';') {
                if (hasStatementContent) {
                    completedStatements++;
                    hasStatementContent = false;
                    if (completedStatements > 1) {
                        throw new SQLException(MULTIPLE_STATEMENTS_ERROR);
                    }
                }
                continue;
            }
            if (!Character.isWhitespace(current)) {
                if (completedStatements > 0) {
                    throw new SQLException(MULTIPLE_STATEMENTS_ERROR);
                }
                hasStatementContent = true;
            }
        }

        if (hasStatementContent) {
            completedStatements++;
        }
        if (completedStatements == 0) {
            throw new SQLException("Console raw execution requires a database command");
        }
        if (completedStatements > 1) {
            throw new SQLException(MULTIPLE_STATEMENTS_ERROR);
        }
    }

    private static int skipLineComment(String sql, int index) {
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
            index++;
        }
        return sql.length() - 1;
    }

    private static String dollarQuoteDelimiter(String sql, int start) {
        if (start > 0 && isIdentifierPart(sql.charAt(start - 1))) {
            return null;
        }
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(start + 1, end);
        if (!tag.isEmpty()) {
            if (!isTagStart(tag.charAt(0))) {
                return null;
            }
            for (int index = 1; index < tag.length(); index++) {
                if (!isTagPart(tag.charAt(index))) {
                    return null;
                }
            }
        }
        String delimiter = sql.substring(start, end + 1);
        return sql.indexOf(delimiter, end + 1) >= 0 ? delimiter : null;
    }

    private static boolean isTagStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean isTagPart(char value) {
        return isTagStart(value) || Character.isDigit(value);
    }

    private static boolean isIdentifierPart(char value) {
        return isTagPart(value) || value == '$';
    }
}
