package org.jumpserver.chen.modules.db2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanJson;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class Db2PlanAccess {
    private static final int MAX_REQUEST_STATEMENT_KEYS = 8;
    private static final List<String> CAPTURE_TABLES = List.of(
            "EXPLAIN_INSTANCE",
            "EXPLAIN_OPERATOR",
            "EXPLAIN_STREAM",
            "EXPLAIN_OBJECT",
            "EXPLAIN_PREDICATE",
            "EXPLAIN_ARGUMENT",
            "EXPLAIN_DIAGNOSTIC",
            "EXPLAIN_DIAGNOSTIC_DATA"
    );
    private static final List<String> DELETE_ORDER = List.of(
            "EXPLAIN_DIAGNOSTIC_DATA",
            "EXPLAIN_DIAGNOSTIC",
            "EXPLAIN_ARGUMENT",
            "EXPLAIN_PREDICATE",
            "EXPLAIN_STREAM",
            "EXPLAIN_OBJECT",
            "EXPLAIN_OPERATOR",
            "EXPLAIN_STATEMENT"
    );

    private Db2PlanAccess() {
    }

    static void explain(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            int queryNo,
            String queryTag
    ) throws SQLException {
        String sql = explainSql(statement.sql(), queryNo, queryTag);
        try (Db2PlanJdbc.Tracked<Statement> tracked = Db2PlanJdbc.statement(context, true)) {
            tracked.statement().execute(sql);
        }
    }

    static String explainSql(String sql, int queryNo, String queryTag) {
        if (queryNo <= 0) {
            throw new IllegalArgumentException("DB2 EXPLAIN QUERYNO must be positive");
        }
        if (queryTag == null || queryTag.isBlank()
                || queryTag.getBytes(StandardCharsets.US_ASCII).length > 20
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(queryTag)) {
            throw new IllegalArgumentException("DB2 EXPLAIN QUERYTAG must be non-empty ASCII and at most 20 bytes");
        }
        return "EXPLAIN PLAN SET QUERYNO=" + queryNo + " SET QUERYTAG='"
                + queryTag.replace("'", "''") + "' FOR " + sql;
    }

    static LocateResult locate(
            PlanExecutionContext context,
            Db2ExplainTables.Target target,
            int queryNo,
            String queryTag,
            Timestamp windowStart,
            Timestamp windowEnd
    ) throws SQLException {
        // DB2 LUW normally writes both an original ('O') and a plan ('P') statement row.
        // Locate one unique plan row first, then collect every full statement key belonging
        // to that same request instance so cleanup removes both layers without a broad delete.
        String sql = "SELECT " + selectColumns(Db2ExplainTables.STATEMENT_KEY)
                + " FROM " + target.qualified("EXPLAIN_STATEMENT")
                + " WHERE EXPLAIN_REQUESTER=? AND QUERYNO=? AND RTRIM(QUERYTAG)=?"
                + " AND EXPLAIN_TIME BETWEEN ? AND ?"
                + " AND EXPLAIN_LEVEL='P'"
                + " FETCH FIRST 2 ROWS ONLY";
        TableMaterialization planKeysTable;
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, sql, false)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, target.identity().authorizationId());
            statement.setInt(2, queryNo);
            statement.setString(3, queryTag);
            statement.setTimestamp(4, windowStart);
            statement.setTimestamp(5, windowEnd);
            try (ResultSet resultSet = statement.executeQuery()) {
                planKeysTable = materialize("EXPLAIN_STATEMENT", resultSet, context.maxRawBytes(), 2);
            }
        }

        List<StatementKey> planKeys = statementKeys(planKeysTable);
        if (planKeys.size() != 1) {
            return new LocateResult(planKeys, List.of(), planKeysTable, false);
        }

        StatementKey planKey = planKeys.get(0);
        String requestWhere = whereKey(Db2ExplainTables.INSTANCE_KEY)
                + " AND QUERYNO=? AND RTRIM(QUERYTAG)=?";
        String requestKeysSql = "SELECT " + selectColumns(Db2ExplainTables.STATEMENT_KEY)
                + " FROM " + target.qualified("EXPLAIN_STATEMENT")
                + " WHERE " + requestWhere
                + " ORDER BY EXPLAIN_LEVEL, STMTNO, SECTNO"
                + " FETCH FIRST " + (MAX_REQUEST_STATEMENT_KEYS + 1) + " ROWS ONLY";
        TableMaterialization requestKeysTable;
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, requestKeysSql, false)) {
            bindRequest(tracked.statement(), planKey, queryNo, queryTag);
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                requestKeysTable = materialize(
                        "EXPLAIN_STATEMENT", resultSet, context.maxRawBytes(),
                        MAX_REQUEST_STATEMENT_KEYS + 1
                );
            }
        }
        List<StatementKey> requestKeys = statementKeys(requestKeysTable);
        boolean complete = !requestKeysTable.truncated()
                && requestKeys.size() <= MAX_REQUEST_STATEMENT_KEYS
                && requestKeys.contains(planKey);

        String statementSql = "SELECT * FROM " + target.qualified("EXPLAIN_STATEMENT")
                + " WHERE " + requestWhere
                + " ORDER BY EXPLAIN_LEVEL, STMTNO, SECTNO";
        TableMaterialization statementTable;
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, statementSql, false)) {
            bindRequest(tracked.statement(), planKey, queryNo, queryTag);
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                statementTable = materialize(
                        "EXPLAIN_STATEMENT", resultSet, context.maxRawBytes(), context.maxNodes()
                );
            }
        }
        return new LocateResult(planKeys, requestKeys, statementTable, complete);
    }

    static Timestamp currentTimestamp(PlanExecutionContext context, boolean useRequestDeadline)
            throws SQLException {
        try (Db2PlanJdbc.Tracked<Statement> tracked =
                     Db2PlanJdbc.statement(context, useRequestDeadline);
             ResultSet resultSet = tracked.statement().executeQuery("VALUES CURRENT TIMESTAMP")) {
            if (!resultSet.next()) {
                throw new SQLException("DB2 current timestamp query returned no row");
            }
            return resultSet.getTimestamp(1);
        }
    }

    static Capture read(
            PlanExecutionContext context,
            Db2ExplainTables.Target target,
            LocateResult located
    ) throws SQLException {
        if (located.keys().size() != 1) {
            return capture(List.of(located.statement()), located.statement().truncated(), context.maxRawBytes());
        }
        StatementKey key = located.keys().get(0);
        List<TableMaterialization> tables = new ArrayList<>();
        tables.add(located.statement());
        boolean truncated = located.statement().truncated();
        int remainingRows = context.maxNodes();

        for (String table : CAPTURE_TABLES) {
            boolean instance = "EXPLAIN_INSTANCE".equals(table);
            String where = whereKey(instance ? Db2ExplainTables.INSTANCE_KEY : Db2ExplainTables.STATEMENT_KEY);
            String sql = "SELECT * FROM " + target.qualified(table) + " WHERE " + where
                    + orderBy(table);
            TableMaterialization materialized;
            try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                         Db2PlanJdbc.preparedStatement(context, sql, true)) {
                bindKey(tracked.statement(), key, instance);
                try (ResultSet resultSet = tracked.statement().executeQuery()) {
                    materialized = materialize(
                            table,
                            resultSet,
                            context.maxRawBytes(),
                            Math.max(0, remainingRows)
                    );
                }
            }
            tables.add(materialized);
            remainingRows -= materialized.rows().size();
            truncated |= materialized.truncated() || remainingRows <= 0;
        }
        return capture(tables, truncated, context.maxRawBytes());
    }

    static void cleanup(
            PlanExecutionContext context,
            Db2ExplainTables.Target target,
            StatementKey key
    ) throws SQLException {
        SQLException failure = null;
        for (String table : DELETE_ORDER) {
            String sql = "DELETE FROM " + target.qualified(table) + " WHERE "
                    + whereKey(Db2ExplainTables.STATEMENT_KEY);
            try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                         Db2PlanJdbc.preparedStatement(context, sql, false)) {
                bindKey(tracked.statement(), key, false);
                tracked.statement().executeUpdate();
            } catch (SQLException e) {
                failure = accumulate(failure, e);
            }
        }

        if (failure == null) {
            String sql = "DELETE FROM " + target.qualified("EXPLAIN_INSTANCE") + " I WHERE "
                    + whereKey("I", Db2ExplainTables.INSTANCE_KEY)
                    + " AND NOT EXISTS (SELECT 1 FROM " + target.qualified("EXPLAIN_STATEMENT")
                    + " S WHERE " + joinInstanceKey("S", "I") + ")";
            try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                         Db2PlanJdbc.preparedStatement(context, sql, false)) {
                bindKey(tracked.statement(), key, true);
                tracked.statement().executeUpdate();
            } catch (SQLException e) {
                failure = accumulate(failure, e);
            }
        }

        if (failure == null) {
            failure = verifyClean(context, target, key);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static SQLException verifyClean(
            PlanExecutionContext context,
            Db2ExplainTables.Target target,
            StatementKey key
    ) {
        SQLException failure = null;
        for (String table : DELETE_ORDER) {
            String sql = "SELECT COUNT(*) FROM " + target.qualified(table) + " WHERE "
                    + whereKey(Db2ExplainTables.STATEMENT_KEY);
            try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                         Db2PlanJdbc.preparedStatement(context, sql, false)) {
                bindKey(tracked.statement(), key, false);
                try (ResultSet resultSet = tracked.statement().executeQuery()) {
                    if (!resultSet.next() || resultSet.getLong(1) != 0L) {
                        failure = accumulate(failure, new SQLException(
                                "DB2 Explain cleanup verification found residual rows in " + table
                        ));
                    }
                }
            } catch (SQLException e) {
                failure = accumulate(failure, e);
            }
        }
        return failure;
    }

    private static TableMaterialization materialize(
            String name,
            ResultSet resultSet,
            int maxRawBytes,
            int maxRows
    ) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        JsonArray columns = new JsonArray();
        List<String> names = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            JsonObject column = new JsonObject();
            String columnName = metadata.getColumnLabel(index);
            names.add(columnName);
            column.addProperty("name", columnName);
            column.addProperty("jdbcType", metadata.getColumnType(index));
            column.addProperty("type", metadata.getColumnTypeName(index));
            columns.add(column);
        }

        List<JsonArray> rows = new ArrayList<>();
        boolean truncated = false;
        int bytes = jsonBytes(columns);
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            JsonArray row = new JsonArray();
            boolean cellTruncated = false;
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Cell cell = jsonValue(resultSet, metadata.getColumnType(index), index, maxRawBytes);
                row.add(cell.value());
                cellTruncated |= cell.truncated();
            }
            int rowBytes = jsonBytes(row);
            if ((long) bytes + rowBytes + 1L > maxRawBytes) {
                truncated = true;
                break;
            }
            rows.add(row);
            bytes += rowBytes + 1;
            truncated |= cellTruncated;
            if (cellTruncated) {
                break;
            }
        }
        return new TableMaterialization(name, columns, List.copyOf(names), List.copyOf(rows), truncated);
    }

    private static Capture capture(
            List<TableMaterialization> tables,
            boolean alreadyTruncated,
            int maxRawBytes
    ) {
        JsonObject envelope = new JsonObject();
        JsonArray resultSets = new JsonArray();
        envelope.add("resultSets", resultSets);
        envelope.addProperty("truncated", alreadyTruncated);
        boolean truncated = alreadyTruncated;
        for (TableMaterialization table : tables) {
            JsonObject json = table.json();
            resultSets.add(json);
            if (jsonBytes(envelope) > maxRawBytes) {
                resultSets.remove(resultSets.size() - 1);
                truncated = true;
                break;
            }
        }
        envelope.addProperty("truncated", truncated);
        String raw = ExecutionPlanJson.GSON.toJson(envelope);
        if (raw.getBytes(StandardCharsets.UTF_8).length > maxRawBytes) {
            JsonObject minimal = new JsonObject();
            minimal.add("resultSets", new JsonArray());
            minimal.addProperty("truncated", true);
            return new Capture(ExecutionPlanJson.GSON.toJson(minimal), true);
        }
        return new Capture(raw, truncated);
    }

    private static Cell jsonValue(ResultSet resultSet, int jdbcType, int index, int maxBytes)
            throws SQLException {
        if (isNumeric(jdbcType)) {
            BigDecimal value = resultSet.getBigDecimal(index);
            return new Cell(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value), false);
        }
        if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
            boolean value = resultSet.getBoolean(index);
            return new Cell(resultSet.wasNull() ? JsonNull.INSTANCE : new JsonPrimitive(value), false);
        }
        if (isBinary(jdbcType)) {
            try (InputStream input = resultSet.getBinaryStream(index)) {
                if (input == null) {
                    return new Cell(JsonNull.INSTANCE, false);
                }
                byte[] value = input.readNBytes(maxBytes + 1);
                boolean truncated = value.length > maxBytes;
                int length = Math.min(value.length, maxBytes);
                return new Cell(new JsonPrimitive(Base64.getEncoder().encodeToString(
                        length == value.length ? value : java.util.Arrays.copyOf(value, length)
                )), truncated);
            } catch (IOException e) {
                throw new SQLException("Failed to read DB2 binary Explain value", e);
            }
        }
        if (isCharacter(jdbcType)) {
            try (Reader reader = resultSet.getCharacterStream(index)) {
                if (reader == null) {
                    return new Cell(JsonNull.INSTANCE, false);
                }
                char[] buffer = new char[Math.min(8192, Math.max(1, maxBytes))];
                StringBuilder value = new StringBuilder();
                int read;
                boolean truncated = false;
                while ((read = reader.read(buffer)) >= 0) {
                    int remaining = maxBytes - value.length();
                    if (remaining <= 0) {
                        truncated = true;
                        break;
                    }
                    value.append(buffer, 0, Math.min(read, remaining));
                    if (read > remaining) {
                        truncated = true;
                        break;
                    }
                }
                return new Cell(new JsonPrimitive(value.toString()), truncated);
            } catch (IOException e) {
                throw new SQLException("Failed to read DB2 character Explain value", e);
            }
        }
        String value = resultSet.getString(index);
        return new Cell(value == null ? JsonNull.INSTANCE : new JsonPrimitive(value), false);
    }

    private static String whereKey(List<String> columns) {
        return String.join(" AND ", columns.stream().map(column -> Db2ExplainTables.quote(column) + "=?").toList());
    }

    private static String selectColumns(List<String> columns) {
        return String.join(", ", columns.stream().map(Db2ExplainTables::quote).toList());
    }

    private static String whereKey(String alias, List<String> columns) {
        return String.join(" AND ", columns.stream()
                .map(column -> alias + "." + Db2ExplainTables.quote(column) + "=?").toList());
    }

    private static String joinInstanceKey(String left, String right) {
        return String.join(" AND ", Db2ExplainTables.INSTANCE_KEY.stream()
                .map(column -> left + "." + Db2ExplainTables.quote(column) + "="
                        + right + "." + Db2ExplainTables.quote(column)).toList());
    }

    private static void bindKey(PreparedStatement statement, StatementKey key, boolean instance)
            throws SQLException {
        statement.setString(1, key.explainRequester());
        statement.setTimestamp(2, key.explainTime());
        statement.setString(3, key.sourceName());
        statement.setString(4, key.sourceSchema());
        statement.setString(5, key.sourceVersion());
        if (!instance) {
            statement.setString(6, key.explainLevel());
            statement.setInt(7, key.stmtNo());
            statement.setInt(8, key.sectNo());
        }
    }

    private static void bindRequest(
            PreparedStatement statement,
            StatementKey key,
            int queryNo,
            String queryTag
    ) throws SQLException {
        bindKey(statement, key, true);
        statement.setInt(6, queryNo);
        statement.setString(7, queryTag);
    }

    private static List<StatementKey> statementKeys(TableMaterialization table) throws SQLException {
        List<StatementKey> keys = new ArrayList<>();
        for (JsonArray row : table.rows()) {
            keys.add(new StatementKey(
                    table.text(row, "EXPLAIN_REQUESTER"),
                    table.timestamp(row, "EXPLAIN_TIME"),
                    table.text(row, "SOURCE_NAME"),
                    table.text(row, "SOURCE_SCHEMA"),
                    table.text(row, "SOURCE_VERSION"),
                    table.text(row, "EXPLAIN_LEVEL"),
                    table.integer(row, "STMTNO"),
                    table.integer(row, "SECTNO")
            ));
        }
        return List.copyOf(keys);
    }

    private static String orderBy(String table) {
        return switch (table) {
            case "EXPLAIN_OPERATOR" -> " ORDER BY OPERATOR_ID";
            case "EXPLAIN_STREAM" -> " ORDER BY STREAM_ID";
            case "EXPLAIN_OBJECT" -> " ORDER BY OBJECT_SCHEMA, OBJECT_NAME, OBJECT_TYPE, CREATE_TIME";
            case "EXPLAIN_PREDICATE" -> " ORDER BY OPERATOR_ID, PREDICATE_ID";
            case "EXPLAIN_ARGUMENT" -> " ORDER BY OPERATOR_ID, ARGUMENT_TYPE";
            case "EXPLAIN_DIAGNOSTIC" -> " ORDER BY DIAGNOSTIC_ID";
            case "EXPLAIN_DIAGNOSTIC_DATA" -> " ORDER BY DIAGNOSTIC_ID, ORDINAL";
            default -> "";
        };
    }

    private static boolean isNumeric(int type) {
        return type == Types.BIGINT || type == Types.DECIMAL || type == Types.DOUBLE
                || type == Types.FLOAT || type == Types.INTEGER || type == Types.NUMERIC
                || type == Types.REAL || type == Types.SMALLINT || type == Types.TINYINT;
    }

    private static boolean isBinary(int type) {
        return type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY
                || type == Types.BLOB;
    }

    private static boolean isCharacter(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR
                || type == Types.CLOB || type == Types.NCLOB;
    }

    private static int jsonBytes(JsonElement value) {
        return ExecutionPlanJson.GSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static SQLException accumulate(SQLException current, SQLException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    record StatementKey(
            String explainRequester,
            Timestamp explainTime,
            String sourceName,
            String sourceSchema,
            String sourceVersion,
            String explainLevel,
            int stmtNo,
            int sectNo
    ) {
        String identity() {
            return explainRequester + "@" + explainTime + "/" + sourceSchema + "." + sourceName
                    + ":" + sourceVersion + "/" + explainLevel + "/" + stmtNo + "/" + sectNo;
        }
    }

    record LocateResult(
            List<StatementKey> keys,
            List<StatementKey> requestKeys,
            TableMaterialization statement,
            boolean complete
    ) {
    }

    record Capture(String json, boolean truncated) {
    }

    private record Cell(JsonElement value, boolean truncated) {
    }

    record TableMaterialization(
            String name,
            JsonArray columns,
            List<String> columnNames,
            List<JsonArray> rows,
            boolean truncated
    ) {
        JsonObject json() {
            JsonObject table = new JsonObject();
            table.addProperty("name", name);
            table.add("columns", columns.deepCopy());
            JsonArray values = new JsonArray();
            rows.forEach(row -> values.add(row.deepCopy()));
            table.add("rows", values);
            return table;
        }

        String text(JsonArray row, String column) throws SQLException {
            JsonElement value = value(row, column);
            return value == null || value.isJsonNull() ? null : value.getAsString().trim();
        }

        int integer(JsonArray row, String column) throws SQLException {
            JsonElement value = value(row, column);
            if (value == null || value.isJsonNull()) {
                throw new SQLException("DB2 Explain statement key column " + column + " is null");
            }
            return value.getAsInt();
        }

        Timestamp timestamp(JsonArray row, String column) throws SQLException {
            String value = text(row, column);
            if (value == null) {
                throw new SQLException("DB2 Explain statement key column " + column + " is null");
            }
            try {
                return Timestamp.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Invalid DB2 Explain timestamp " + value, e);
            }
        }

        private JsonElement value(JsonArray row, String column) throws SQLException {
            for (int index = 0; index < columnNames.size(); index++) {
                if (column.equalsIgnoreCase(columnNames.get(index))) {
                    return row.get(index);
                }
            }
            throw new SQLException("DB2 Explain result is missing column " + column);
        }
    }
}
