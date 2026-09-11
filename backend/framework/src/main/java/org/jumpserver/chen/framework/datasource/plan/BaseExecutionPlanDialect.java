package org.jumpserver.chen.framework.datasource.plan;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
        return new BoundedRaw(new String(bytes, 0, utf8PrefixLength(bytes, maxRawBytes), StandardCharsets.UTF_8), true);
    }

    protected static int utf8ByteLength(CharSequence value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    protected static int utf8PrefixLength(byte[] bytes, int maxBytes) {
        int length = Math.min(bytes.length, Math.max(0, maxBytes));
        if (length >= bytes.length) {
            return length;
        }
        while (length > 0 && (bytes[length - 1] & 0xC0) == 0x80) {
            length--;
        }
        if (length > 0 && (bytes[length - 1] & 0x80) != 0) {
            length--;
        }
        return length;
    }

    protected void throwIfConnectionUnusable(
            PlanExecutionContext context,
            SQLException error,
            ExecutionPlanCapabilities capabilities
    ) throws SQLException {
        if (!connectionUnusable(context, error)) {
            return;
        }
        String message = error.getMessage() == null
                ? "Connection became unusable during execution plan"
                : error.getMessage();
        PlanDiagnostic diagnostic = new PlanDiagnostic(
                PlanCodes.CONNECTION_INVALIDATED,
                message,
                error.getSQLState(),
                Integer.toString(error.getErrorCode())
        );
        throw new ConnectionInvalidatedException(
                message,
                error,
                diagnostic,
                null,
                new DialectPlanResult(
                        context.serverVersion(),
                        PlanStatus.CONNECTION_INVALIDATED,
                        List.of(),
                        null,
                        null,
                        null,
                        false,
                        capabilities,
                        List.of(),
                        PlanEffects.discard(PlanEffects.SessionState.UNKNOWN, PlanEffects.TransactionState.UNKNOWN),
                        diagnostic,
                        List.of()
                )
        );
    }

    protected static boolean connectionUnusable(PlanExecutionContext context, SQLException error) {
        String state = error.getSQLState();
        if (state != null && state.startsWith("08")) {
            return true;
        }
        Connection connection = context.connection();
        if (connection == null) {
            return true;
        }
        try {
            if (connection.isClosed()) {
                return true;
            }
        } catch (SQLException e) {
            return true;
        }
        try {
            return !connection.isValid(1);
        } catch (SQLException | AbstractMethodError | RuntimeException ignored) {
            return false;
        }
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
