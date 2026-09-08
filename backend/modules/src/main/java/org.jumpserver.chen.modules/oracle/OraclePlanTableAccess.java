package org.jumpserver.chen.modules.oracle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanJson;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

final class OraclePlanTableAccess {
    private OraclePlanTableAccess() {
    }

    static void explain(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            OraclePlanTable.Target target,
            String statementId
    ) throws SQLException {
        String sql = "EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' INTO "
                + target.qualifiedName() + " FOR " + statement.sql();
        try (OraclePlanJdbc.Tracked<Statement> tracked = OraclePlanJdbc.statement(context, true)) {
            tracked.statement().execute(sql);
        }
    }

    static Capture read(
            PlanExecutionContext context,
            OraclePlanTable.Target target,
            String statementId
    ) throws SQLException {
        List<String> columns = target.selectedColumns();
        String selected = columns.stream().map(OraclePlanTable::quote).collect(Collectors.joining(", "));
        String sql = "SELECT " + selected + " FROM " + target.qualifiedName()
                + " WHERE " + OraclePlanTable.quote("STATEMENT_ID") + " = ? ORDER BY "
                + OraclePlanTable.quote("ID") + ", " + OraclePlanTable.quote("POSITION");

        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked =
                     OraclePlanJdbc.preparedStatement(context, sql, true)) {
            tracked.statement().setString(1, statementId);
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                return capture(resultSet, context.maxRawBytes(), context.maxNodes());
            }
        }
    }

    static void delete(
            PlanExecutionContext context,
            OraclePlanTable.Target target,
            String statementId
    ) throws SQLException {
        String sql = "DELETE FROM " + target.qualifiedName()
                + " WHERE " + OraclePlanTable.quote("STATEMENT_ID") + " = ?";
        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked =
                     OraclePlanJdbc.preparedStatement(context, sql, false)) {
            tracked.statement().setString(1, statementId);
            tracked.statement().executeUpdate();
        }
    }

    private static Capture capture(ResultSet resultSet, int maxRawBytes, int maxRows) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        JsonObject envelope = new JsonObject();
        JsonArray resultSets = new JsonArray();
        JsonObject table = new JsonObject();
        table.addProperty("name", "PLAN_TABLE");
        JsonArray columns = new JsonArray();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            JsonObject column = new JsonObject();
            column.addProperty("name", metadata.getColumnLabel(index));
            column.addProperty("type", metadata.getColumnTypeName(index));
            columns.add(column);
        }
        table.add("columns", columns);
        JsonArray rows = new JsonArray();
        table.add("rows", rows);
        resultSets.add(table);
        envelope.add("resultSets", resultSets);
        envelope.addProperty("truncated", false);

        boolean truncated = false;
        int rowCount = 0;
        int capturedBytes = jsonBytes(envelope);
        while (resultSet.next()) {
            if (rowCount >= maxRows) {
                truncated = true;
                break;
            }
            JsonArray row = new JsonArray();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                row.add(jsonValue(resultSet, metadata.getColumnType(index), index));
            }
            int rowBytes = jsonBytes(row);
            int separatorBytes = rows.isEmpty() ? 0 : 1;
            if ((long) capturedBytes + separatorBytes + rowBytes > maxRawBytes) {
                truncated = true;
                break;
            }
            rows.add(row);
            capturedBytes += separatorBytes + rowBytes;
            rowCount++;
        }
        envelope.addProperty("truncated", truncated);
        String json = ExecutionPlanJson.GSON.toJson(envelope);
        if (json.getBytes(StandardCharsets.UTF_8).length > maxRawBytes) {
            JsonObject minimal = new JsonObject();
            minimal.add("resultSets", new JsonArray());
            minimal.addProperty("truncated", true);
            return new Capture(ExecutionPlanJson.GSON.toJson(minimal), true);
        }
        return new Capture(json, truncated);
    }

    private static int jsonBytes(JsonElement value) {
        return ExecutionPlanJson.GSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonElement jsonValue(ResultSet resultSet, int jdbcType, int index) throws SQLException {
        if (isNumericJdbcType(jdbcType)) {
            BigDecimal value = resultSet.getBigDecimal(index);
            return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
        }
        if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
            boolean value = resultSet.getBoolean(index);
            return resultSet.wasNull() ? JsonNull.INSTANCE : new JsonPrimitive(value);
        }
        String value = resultSet.getString(index);
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    private static boolean isNumericJdbcType(int jdbcType) {
        return jdbcType == Types.BIGINT || jdbcType == Types.DECIMAL || jdbcType == Types.DOUBLE
                || jdbcType == Types.FLOAT || jdbcType == Types.INTEGER || jdbcType == Types.NUMERIC
                || jdbcType == Types.REAL || jdbcType == Types.SMALLINT || jdbcType == Types.TINYINT;
    }

    record Capture(String json, boolean truncated) {
    }
}
