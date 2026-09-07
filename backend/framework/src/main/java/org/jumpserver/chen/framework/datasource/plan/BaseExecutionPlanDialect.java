package org.jumpserver.chen.framework.datasource.plan;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public abstract class BaseExecutionPlanDialect implements ExecutionPlanDialect {
    protected String readFirstColumn(ResultSet resultSet, int maxRawBytes) throws SQLException {
        if (resultSet == null || !resultSet.next()) {
            return "";
        }
        String value = resultSet.getString(1);
        return boundRaw(value, maxRawBytes).text();
    }

    protected BoundedRaw boundRaw(String value, int maxRawBytes) {
        if (value == null) {
            return new BoundedRaw("", false);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxRawBytes) {
            return new BoundedRaw(value, false);
        }
        String truncated = new String(bytes, 0, maxRawBytes, StandardCharsets.UTF_8);
        return new BoundedRaw(truncated, true);
    }

    protected void closeQuietly(Statement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException ignored) {
        }
    }

    protected void closeQuietly(ResultSet resultSet) {
        if (resultSet == null) {
            return;
        }
        try {
            resultSet.close();
        } catch (SQLException ignored) {
        }
    }

    public record BoundedRaw(String text, boolean truncated) {
    }
}
