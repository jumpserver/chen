package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanLimits;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SqlServerPlanJdbc {
    private static final int MAX_RESULTS = 64;
    private static final int MAX_XML_DOCUMENTS = 16;

    private SqlServerPlanJdbc() {
    }

    static TrackedStatement requestStatement(PlanExecutionContext context) throws SQLException {
        context.throwIfCancelled();
        Statement statement = context.connection().createStatement();
        context.registerStatement(statement);
        try {
            configureTimeout(statement, Math.max(1L, context.deadlineMillis() - System.currentTimeMillis()));
            context.throwIfCancelled();
            return new TrackedStatement(statement, context, true);
        } catch (SQLException e) {
            context.unregisterStatement(statement);
            closeAfterFailure(statement, e);
            throw e;
        }
    }

    static TrackedStatement cleanupStatement(PlanExecutionContext context) throws SQLException {
        Statement statement = context.connection().createStatement();
        try {
            configureTimeout(statement, PlanLimits.CLEANUP_TIMEOUT_MS);
            return new TrackedStatement(statement, context, false);
        } catch (SQLException e) {
            closeAfterFailure(statement, e);
            throw e;
        }
    }

    static void executeAndDrain(Statement statement, String sql) throws SQLException {
        boolean resultSet = statement.execute(sql);
        int traversed = 0;
        while (true) {
            if (++traversed > MAX_RESULTS) {
                throw new SQLException("SQL Server returned too many JDBC results", "HY000");
            }
            if (resultSet) {
                try (ResultSet ignored = statement.getResultSet()) {
                    // SET SHOWPLAN_XML should not return rows. Closing any unexpected result is enough
                    // before advancing because the Microsoft driver supports CLOSE_CURRENT_RESULT.
                }
            } else if (statement.getUpdateCount() == -1) {
                return;
            }
            resultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
    }

    static ProbeCapture executeProbe(Statement statement, String sql, int maxBytes) throws SQLException {
        boolean resultSet = statement.execute(sql);
        int traversed = 0;
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (true) {
            if (++traversed > MAX_RESULTS) {
                throw new SQLException("SQL Server probe returned too many JDBC results", "HY000");
            }
            if (resultSet) {
                try (ResultSet current = statement.getResultSet()) {
                    if (current != null) {
                        ResultSetMetaData metadata = current.getMetaData();
                        int columns = metadata == null ? 1 : Math.max(1, metadata.getColumnCount());
                        while (current.next()) {
                            List<String> row = new ArrayList<>(columns);
                            for (int column = 1; column <= columns; column++) {
                                BoundedText value = readProbeValue(current, column, maxBytes);
                                row.add(value.text());
                                truncated |= value.truncated();
                            }
                            rows.add(List.copyOf(row));
                            if (rows.size() >= MAX_XML_DOCUMENTS) {
                                truncated = true;
                                break;
                            }
                        }
                    }
                }
            } else if (statement.getUpdateCount() == -1) {
                return new ProbeCapture(List.copyOf(rows), truncated);
            }
            resultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
    }

    static PlanCapture executePlan(Statement statement, String sql, int maxRawBytes) throws SQLException {
        boolean resultSet = statement.execute(sql);
        int traversed = 0;
        List<String> documents = new ArrayList<>();
        boolean truncated = false;
        int capturedBytes = 0;
        while (true) {
            if (++traversed > MAX_RESULTS) {
                throw new SQLException("SQL Server returned too many JDBC results", "HY000");
            }
            if (resultSet) {
                try (ResultSet current = statement.getResultSet()) {
                    while (!truncated && current != null && current.next()) {
                        if (documents.size() >= MAX_XML_DOCUMENTS) {
                            truncated = true;
                            break;
                        }
                        int remainingBytes = Math.max(0, maxRawBytes - capturedBytes);
                        BoundedText document = readPlanXml(current, remainingBytes);
                        if (document.text() != null && !document.text().isBlank()) {
                            documents.add(document.text());
                            capturedBytes += document.text().getBytes(StandardCharsets.UTF_8).length;
                        }
                        truncated |= document.truncated();
                        if (document.truncated()) {
                            break;
                        }
                    }
                }
            } else if (statement.getUpdateCount() == -1) {
                return capture(documents, truncated, maxRawBytes);
            }
            resultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
    }

    private static PlanCapture capture(List<String> documents, boolean truncated, int maxRawBytes) {
        String rawText;
        if (documents.isEmpty()) {
            rawText = "";
        } else if (documents.size() == 1) {
            rawText = documents.get(0);
        } else {
            StringBuilder joined = new StringBuilder();
            for (int index = 0; index < documents.size(); index++) {
                if (index > 0) {
                    joined.append("\n<!-- Chen SHOWPLAN document boundary -->\n");
                }
                joined.append(documents.get(index));
            }
            rawText = joined.toString();
        }
        BoundedText bounded = bound(rawText, maxRawBytes);
        return new PlanCapture(List.copyOf(documents), bounded.text(), truncated || bounded.truncated());
    }

    private static BoundedText readPlanXml(ResultSet resultSet, int maxBytes) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        boolean xmlColumn = false;
        if (metadata != null) {
            try {
                String typeName = metadata.getColumnTypeName(1);
                xmlColumn = metadata.getColumnType(1) == Types.SQLXML
                        || (typeName != null && "xml".equals(typeName.toLowerCase(Locale.ROOT)));
            } catch (SQLException ignored) {
                // The value is still readable through the JDBC character-stream mapping.
            }
        }
        if (xmlColumn) {
            try {
                SQLXML sqlxml = resultSet.getSQLXML(1);
                if (sqlxml == null) {
                    return new BoundedText("", false);
                }
                return readAndFree(sqlxml, maxBytes);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Older compatible drivers may expose the XML column only as a character stream.
            }
        }
        Reader reader = resultSet.getCharacterStream(1);
        if (reader != null) {
            return read(reader, maxBytes);
        }
        return bound(resultSet.getString(1), maxBytes);
    }

    private static BoundedText readProbeValue(ResultSet resultSet, int column, int maxBytes) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value instanceof SQLXML sqlxml) {
            return readAndFree(sqlxml, maxBytes);
        }
        return bound(value == null ? null : value.toString(), maxBytes);
    }

    private static BoundedText readAndFree(SQLXML sqlxml, int maxBytes) throws SQLException {
        BoundedText value = null;
        SQLException failure = null;
        try {
            value = read(sqlxml.getCharacterStream(), maxBytes);
        } catch (SQLException e) {
            failure = e;
        }
        try {
            sqlxml.free();
        } catch (SQLException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return value;
    }

    private static BoundedText read(Reader reader, int maxBytes) throws SQLException {
        if (reader == null) {
            return new BoundedText("", false);
        }
        int characterLimit = Math.max(0, maxBytes) + 1;
        StringBuilder value = new StringBuilder(Math.min(characterLimit, 8192));
        char[] buffer = new char[4096];
        try (reader) {
            while (value.length() < characterLimit) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, characterLimit - value.length()));
                if (read < 0) {
                    break;
                }
                value.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read SQL Server XML plan", "HY000", e);
        }
        return bound(value.toString(), maxBytes);
    }

    private static BoundedText bound(String value, int maxBytes) {
        if (value == null) {
            return new BoundedText("", false);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int safeMaxBytes = Math.max(0, maxBytes);
        if (bytes.length <= safeMaxBytes) {
            return new BoundedText(value, false);
        }
        return new BoundedText(new String(bytes, 0, safeMaxBytes, StandardCharsets.UTF_8), true);
    }

    private static void configureTimeout(Statement statement, long timeoutMillis) throws SQLException {
        long seconds = Math.max(1L, (timeoutMillis + 999L) / 1_000L);
        statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
    }

    private static void closeAfterFailure(Statement statement, SQLException error) {
        try {
            statement.close();
        } catch (SQLException closeError) {
            error.addSuppressed(closeError);
        }
    }

    record ProbeCapture(List<List<String>> rows, boolean truncated) {
    }

    record PlanCapture(List<String> documents, String rawText, boolean truncated) {
    }

    private record BoundedText(String text, boolean truncated) {
    }

    static final class TrackedStatement implements AutoCloseable {
        private final Statement statement;
        private final PlanExecutionContext context;
        private final boolean registered;

        private TrackedStatement(Statement statement, PlanExecutionContext context, boolean registered) {
            this.statement = statement;
            this.context = context;
            this.registered = registered;
        }

        Statement statement() {
            return statement;
        }

        @Override
        public void close() throws SQLException {
            if (registered) {
                context.unregisterStatement(statement);
            }
            statement.close();
        }
    }
}
