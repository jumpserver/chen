package org.jumpserver.chen.modules.postgresql;

import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.BaseExecutionPlanDialect;
import org.jumpserver.chen.framework.datasource.plan.ConnectionInvalidatedException;
import org.jumpserver.chen.framework.datasource.plan.DialectPlanResult;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanCapabilities;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDatabase;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanEffects;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanPrerequisite;
import org.jumpserver.chen.framework.datasource.plan.PlanRawFormat;
import org.jumpserver.chen.framework.datasource.plan.PlanStatus;
import org.jumpserver.chen.framework.datasource.plan.PlanTransactionState;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class PostgresqlExecutionPlanDialect extends BaseExecutionPlanDialect {
    static final String RAW_FORMAT_VERSION = "postgresql-explain-json";
    private static final Set<String> SAFE_SCHEMAS = Set.of("pg_catalog", "information_schema");
    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "coalesce", "nullif", "greatest", "least",
            "now", "current_date", "current_time", "current_timestamp", "localtimestamp", "localtime",
            "date_trunc", "date_part", "age", "extract", "to_char", "to_date", "to_timestamp", "to_number",
            "lower", "upper", "trim", "ltrim", "rtrim", "length", "char_length", "octet_length",
            "substring", "substr", "replace", "overlay", "position", "strpos", "concat", "concat_ws",
            "format", "left", "right", "repeat", "reverse", "split_part", "regexp_replace", "regexp_split_to_array",
            "abs", "ceil", "ceiling", "floor", "round", "trunc", "mod", "power", "sqrt", "sign",
            "generate_series", "unnest", "array_agg", "string_agg", "json_agg", "jsonb_agg", "json_build_object",
            "jsonb_build_object", "json_build_array", "jsonb_build_array", "row_to_json", "to_json", "to_jsonb",
            "row_number", "rank", "dense_rank", "ntile", "lag", "lead", "first_value", "last_value", "nth_value",
            "bool_and", "bool_or", "every", "corr", "covar_pop", "covar_samp", "regr_avgx", "stddev", "variance",
            "exists", "cast", "text", "int", "int2", "int4", "int8", "float4", "float8", "numeric", "varchar",
            "bpchar", "bool", "date", "timestamp", "timestamptz", "uuid", "json", "jsonb", "bytea",
            "pg_typeof", "pg_get_expr", "pg_get_userbyid", "current_schema", "current_user", "session_user",
            "array_length", "cardinality", "array_append", "array_cat", "array_remove",
            "md5", "sha256", "encode", "decode", "btrim", "initcap", "quote_ident", "quote_literal"
    );
    private static final Set<String> UNSAFE_FUNCTIONS = Set.of(
            "dblink", "dblink_exec", "dblink_connect", "lo_import", "lo_export", "lo_unlink",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file", "pg_execute_server_program",
            "pg_reload_conf", "pg_terminate_backend", "pg_cancel_backend", "copy_from", "copy_to"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.postgresql();

    @Override
    public PlanDatabase database() {
        return PlanDatabase.postgresql;
    }

    @Override
    public ExecutionPlanCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public DialectPlanResult explainEstimated(PlanExecutionContext context, SqlStatementAnalysis statement)
            throws SQLException {
        List<PlanPrerequisite> prerequisites = new ArrayList<>();
        PlanDiagnostic unsafe = unsafeFunctions(statement);
        if (unsafe != null) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.UNSAFE_TO_ESTIMATE,
                    unsafe.message(),
                    "Remove user-defined or planning-time-unsafe functions and retry"
            ));
            return unmet(context, prerequisites, unsafe, PlanEffects.unchangedReuse());
        }

        PlanTransactionState txState = context.transactionState();
        if (context.transactionProbeFailed()) {
            String detail = context.transactionProbeDetail();
            String message = (detail == null || detail.isBlank())
                    ? "PostgreSQL transaction state probe failed"
                    : "PostgreSQL transaction state probe failed: " + detail;
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    message,
                    "Reconnect and retry without relying on SAVEPOINT probing"
            ));
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message),
                    PlanEffects.unchangedReuse()
            );
        }
        if (txState == PlanTransactionState.UNKNOWN) {
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "PostgreSQL transaction state is unknown",
                    "Reconnect and retry without relying on SAVEPOINT probing"
            ));
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, "PostgreSQL transaction state is unknown"),
                    PlanEffects.unchangedReuse()
            );
        }
        if (txState == PlanTransactionState.TRANSACTION_FAILED) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "The current PostgreSQL transaction is already aborted",
                    "Rollback or finish the existing transaction, then retry"
            ));
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, "Cannot estimate a plan in a failed transaction"),
                    PlanEffects.unchangedReuse()
            );
        }
        if (txState == PlanTransactionState.MANUAL_COMMIT_IDLE) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "A manual-commit idle connection cannot start a plan transaction",
                    "Run EXPLAIN while auto-commit idle or inside an already-open transaction"
            ));
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, "Manual-commit idle connections are not used for EXPLAIN"),
                    PlanEffects.unchangedReuse()
            );
        }

        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT"));
        boolean useSavepoint = txState == PlanTransactionState.TRANSACTION_ACTIVE;
        if (useSavepoint) {
            return explainWithSavepoint(context, statement, prerequisites);
        }
        return explainDirect(context, statement, prerequisites, PlanEffects.unchangedReuse());
    }

    private DialectPlanResult explainWithSavepoint(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        Connection connection = context.connection();
        String savepointName = "chen_ep_" + HexFormat.of().formatHex(randomBytes(6));
        try {
            context.throwIfCancelled();
            executeSql(context, connection, "SAVEPOINT " + savepointName);
        } catch (SQLException e) {
            throwIfInvalid(context, e, PlanEffects.unchangedReuse());
            return unmet(
                    context,
                    List.of(PlanPrerequisite.unmet(
                            PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                            "Failed to create a request-scoped SAVEPOINT",
                            "Check the transaction state and retry"
                    )),
                    sqlDiagnostic(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, "Failed to create SAVEPOINT", e),
                    PlanEffects.unchangedReuse()
            );
        }

        SQLException explainError = null;
        DialectPlanResult result = null;
        try {
            result = explainDirect(context, statement, prerequisites, PlanEffects.savepointParticipated());
        } catch (SQLException e) {
            explainError = e;
        }

        SQLException restoreError = null;
        try {
            if (explainError != null) {
                executeSql(context, connection, "ROLLBACK TO SAVEPOINT " + savepointName);
            }
            executeSql(context, connection, "RELEASE SAVEPOINT " + savepointName);
        } catch (SQLException e) {
            restoreError = e;
        }

        if (restoreError != null) {
            PlanDiagnostic original = explainError == null
                    ? PlanDiagnostic.of(PlanCodes.SQL_ERROR, "SAVEPOINT restore failed after EXPLAIN")
                    : sqlDiagnostic(PlanCodes.SQL_ERROR, explainError.getMessage(), explainError);
            throw new ConnectionInvalidatedException(
                    "PostgreSQL SAVEPOINT restore failed",
                    restoreError,
                    original,
                    sqlDiagnostic(PlanCodes.CONNECTION_INVALIDATED, restoreError.getMessage(), restoreError),
                    result
            );
        }
        if (explainError != null) {
            throwIfInvalid(context, explainError, PlanEffects.savepointParticipated());
            throw explainError;
        }
        return result;
    }

    private DialectPlanResult explainDirect(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects
    ) throws SQLException {
        context.throwIfCancelled();
        Connection connection = context.connection();
        Statement jdbcStatement = null;
        ResultSet resultSet = null;
        try {
            jdbcStatement = connection.createStatement();
            context.registerStatement(jdbcStatement);
            BoundedRaw raw;
            try {
                resultSet = jdbcStatement.executeQuery("EXPLAIN (FORMAT JSON) " + statement.sql());
                String value = resultSet.next() ? resultSet.getString(1) : "";
                raw = boundRaw(value, context.maxRawBytes());
            } finally {
                closeQuietly(resultSet);
                context.unregisterStatement(jdbcStatement);
                closeQuietly(jdbcStatement);
            }

            if (raw.truncated()) {
                return new DialectPlanResult(
                        context.serverVersion(),
                        PlanStatus.RAW_ONLY,
                        List.of(),
                        raw.text(),
                        PlanRawFormat.JSON,
                        RAW_FORMAT_VERSION,
                        true,
                        capabilities,
                        prerequisites,
                        effects,
                        null,
                        List.of(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Raw plan exceeded the display limit"))
                );
            }

            PostgresqlPlanParser.ParseResult parsed = PostgresqlPlanParser.parse(
                    raw.text(),
                    context.maxNodes(),
                    context.maxDepth()
            );
            if (!parsed.structured()) {
                return new DialectPlanResult(
                        context.serverVersion(),
                        PlanStatus.RAW_ONLY,
                        List.of(),
                        raw.text(),
                        PlanRawFormat.JSON,
                        RAW_FORMAT_VERSION,
                        false,
                        capabilities,
                        prerequisites,
                        effects,
                        null,
                        parsed.warnings()
                );
            }
            return new DialectPlanResult(
                    context.serverVersion(),
                    PlanStatus.SUCCESS,
                    parsed.roots(),
                    raw.text(),
                    PlanRawFormat.JSON,
                    RAW_FORMAT_VERSION,
                    false,
                    capabilities,
                    prerequisites,
                    effects,
                    null,
                    parsed.warnings()
            );
        } catch (SQLException e) {
            throwIfInvalid(context, e, effects);
            throw e;
        }
    }

    private void executeSql(PlanExecutionContext context, Connection connection, String sql) throws SQLException {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            context.registerStatement(statement);
            statement.execute(sql);
        } finally {
            context.unregisterStatement(statement);
            closeQuietly(statement);
        }
    }

    private static void throwIfInvalid(PlanExecutionContext context, SQLException error, PlanEffects effects)
            throws SQLException {
        if (isConnectionBroken(error)) {
            throw new ConnectionInvalidatedException(
                    error.getMessage(),
                    error,
                    sqlDiagnostic(PlanCodes.CONNECTION_INVALIDATED, error.getMessage(), error),
                    null,
                    new DialectPlanResult(
                            context.serverVersion(),
                            PlanStatus.CONNECTION_INVALIDATED,
                            List.of(),
                            null,
                            null,
                            null,
                            false,
                            ExecutionPlanCapabilities.postgresql(),
                            List.of(),
                            PlanEffects.discard(PlanEffects.SessionState.UNKNOWN, PlanEffects.TransactionState.UNKNOWN),
                            sqlDiagnostic(PlanCodes.CONNECTION_INVALIDATED, error.getMessage(), error),
                            List.of()
                    )
            );
        }
    }

    private static boolean isConnectionBroken(SQLException error) {
        String state = error.getSQLState();
        return state != null && (state.startsWith("08") || "57P01".equals(state) || "57P02".equals(state) || "57P03".equals(state));
    }

    private DialectPlanResult unmet(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites,
            PlanDiagnostic error,
            PlanEffects effects
    ) {
        return new DialectPlanResult(
                context.serverVersion(),
                PlanStatus.PREREQUISITES_UNMET,
                List.of(),
                null,
                null,
                null,
                false,
                capabilities,
                prerequisites,
                effects,
                error,
                List.of()
        );
    }

    static PlanDiagnostic unsafeFunctions(SqlStatementAnalysis statement) {
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String schema = dot < 0 ? "" : lower.substring(0, dot).replace("\"", "");
            String function = (dot < 0 ? lower : lower.substring(dot + 1)).replace("\"", "");
            if (UNSAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is not safe to estimate at planning time"
                );
            }
            if (!schema.isEmpty() && !SAFE_SCHEMAS.contains(schema)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is outside pg_catalog and cannot be proven side-effect free"
                );
            }
            if (schema.isEmpty() && !BUILTIN_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is not a known PostgreSQL builtin"
                );
            }
        }
        return null;
    }

    private static PlanDiagnostic sqlDiagnostic(String code, String message, SQLException error) {
        return new PlanDiagnostic(
                code,
                message == null ? error.getMessage() : message,
                error.getSQLState(),
                Integer.toString(error.getErrorCode())
        );
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
