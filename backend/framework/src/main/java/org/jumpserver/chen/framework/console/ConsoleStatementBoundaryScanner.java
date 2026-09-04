package org.jumpserver.chen.framework.console;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits raw console input on top-level statement boundaries without rewriting it.
 */
public final class ConsoleStatementBoundaryScanner {
    private ConsoleStatementBoundaryScanner() {
    }

    public static boolean hasExactlyOneStatement(String sql) throws SQLException {
        return split(sql).size() == 1;
    }

    static List<String> split(String sql) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("Console raw execution requires a database command");
        }

        List<String> statements = splitOpaqueStatements(sql);
        if (statements.isEmpty()) {
            throw new SQLException("Console raw execution requires a database command");
        }
        return statements;
    }

    private static List<String> splitOpaqueStatements(String sql) {
        List<String> statements = new ArrayList<>();
        int statementStart = 0;
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
                    addStatement(statements, sql, statementStart, index + 1);
                    hasStatementContent = false;
                }
                statementStart = index + 1;
                continue;
            }
            if (!Character.isWhitespace(current)) {
                hasStatementContent = true;
            }
        }

        if (hasStatementContent) {
            addStatement(statements, sql, statementStart, sql.length());
        }
        return statements;
    }

    private static void addStatement(List<String> statements, String sql, int start, int end) {
        String statement = sql.substring(start, end).trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
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
