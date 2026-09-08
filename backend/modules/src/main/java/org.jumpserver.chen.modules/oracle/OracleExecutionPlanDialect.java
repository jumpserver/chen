package org.jumpserver.chen.modules.oracle;

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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class OracleExecutionPlanDialect extends BaseExecutionPlanDialect {
    static final String RAW_FORMAT_VERSION = "chen-table-v1";
    private static final String ORACLE_19C_BASELINE = "Oracle 19c validated baseline";
    private static final String ORACLE_26AI_23_26_BASELINE =
            "Oracle AI Database 26ai 23.26.x validated baseline";
    private static final Pattern ORACLE_DATABASE_PRODUCT = Pattern.compile(
            "\\bOracle(?:\\s+AI)?\\s+Database\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ORACLE_19C_VERSION = Pattern.compile(
            "(?:\\b19c\\b|(?<![\\d.])19(?:\\.\\d+){4}(?![\\d.]))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ORACLE_26AI_PRODUCT = Pattern.compile(
            "\\bOracle\\s+(?:AI\\s+Database|Database)\\s+26\\s*ai\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ORACLE_26AI_23_26_VERSION = Pattern.compile(
            "(?<![\\d.])23\\.26(?:\\.\\d+){3}(?![\\d.])"
    );

    private static final Set<String> SAFE_SCHEMAS = Set.of("sys", "standard");
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
            "abs", "acos", "add_months", "ascii", "asin", "atan", "atan2", "avg", "bitand",
            "cast", "ceil", "chr", "coalesce", "concat", "convert", "corr", "cos", "cosh", "count",
            "covar_pop", "covar_samp", "decode", "dense_rank", "dump", "exp", "extract", "first_value",
            "floor", "greatest", "instr", "instrb", "json_array", "json_arrayagg", "json_exists",
            "json_object", "json_objectagg", "json_query", "json_value", "lag", "last_day", "last_value",
            "lead", "least", "length", "lengthb", "listagg", "ln", "log", "lower", "lpad", "ltrim",
            "max", "median", "min", "mod", "months_between", "next_day", "nth_value", "ntile", "nullif",
            "nvl", "nvl2", "ora_hash", "percent_rank", "percentile_cont", "percentile_disc", "power",
            "rank", "regexp_count", "regexp_instr", "regexp_like", "regexp_replace", "regexp_substr",
            "replace", "round", "row_number", "rpad", "rtrim", "sign", "sin", "sinh", "sqrt",
            "standard_hash", "stddev", "stddev_pop", "stddev_samp", "substr", "substrb", "sum", "tan",
            "tanh", "to_binary_double", "to_binary_float", "to_char", "to_clob", "to_date", "to_number",
            "to_timestamp", "to_timestamp_tz", "translate", "trim", "trunc", "upper", "variance", "xmlagg",
            "xmlelement", "xmlforest", "xmlserialize"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.oracle();

    @Override
    public PlanDatabase database() {
        return PlanDatabase.oracle;
    }

    @Override
    public ExecutionPlanCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public DialectPlanResult explainEstimated(PlanExecutionContext context, SqlStatementAnalysis statement)
            throws SQLException {
        try {
            return explain(context, statement);
        } catch (ConnectionInvalidatedException e) {
            throw e;
        } catch (SQLException e) {
            if (isConnectionBroken(e)) {
                throw invalidated(context, e, null, PlanEffects.discard(
                        PlanEffects.SessionState.UNKNOWN,
                        PlanEffects.TransactionState.UNKNOWN
                ));
            }
            throw e;
        }
    }

    private DialectPlanResult explain(PlanExecutionContext context, SqlStatementAnalysis statement)
            throws SQLException {
        List<PlanPrerequisite> prerequisites = new ArrayList<>();
        PlanDiagnostic unsafe = unsafeFunctions(statement);
        if (unsafe != null) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.UNSAFE_TO_ESTIMATE,
                    unsafe.message(),
                    "Remove user-defined or planning-time-unsafe functions and retry"
            ));
            return unmet(context, prerequisites, unsafe);
        }

        String validatedVersionBaseline = validatedVersionBaseline(context.serverVersion());
        if (validatedVersionBaseline == null) {
            String version = context.serverVersion();
            String message = version == null || version.isBlank()
                    ? "Oracle server version could not be determined; continuing with runtime capability checks"
                    : "Oracle server version has not been validated for estimated plans; "
                            + "continuing with runtime capability checks: " + version;
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED,
                    message,
                    "Verify that the PLAN_TABLE, privilege, and transaction prerequisites pass"
            ));
        } else {
            prerequisites.add(PlanPrerequisite.met("server-version", validatedVersionBaseline));
        }

        TransactionCheck transaction = checkTransaction(context);
        if (transaction.failure() != null) {
            prerequisites.add(transaction.failure());
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, transaction.failure().message())
            );
        }
        prerequisites.add(PlanPrerequisite.met("transaction", transaction.message()));

        OraclePlanTable.Resolution resolution = OraclePlanTable.resolve(context);
        if (resolution.failure() != null) {
            prerequisites.add(resolution.failure());
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(resolution.failure().code(), resolution.failure().message())
            );
        }
        OraclePlanTable.Target target = resolution.target();
        prerequisites.add(PlanPrerequisite.met(
                "plan-table",
                "Using " + target.qualifiedName() + (target.temporary() ? " (global temporary)" : "")
        ));

        PlanPrerequisite lifecycleFailure = OraclePlanTable.validateLifecycle(target, transaction.autoCommit());
        if (lifecycleFailure != null) {
            prerequisites.add(lifecycleFailure);
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(lifecycleFailure.code(), lifecycleFailure.message())
            );
        }
        prerequisites.add(PlanPrerequisite.met(
                "plan-row-lifecycle",
                OraclePlanTable.lifecycleDescription(target)
        ));

        PlanPrerequisite columnFailure = OraclePlanTable.validateColumns(target);
        if (columnFailure != null) {
            prerequisites.add(columnFailure);
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(columnFailure.code(), columnFailure.message())
            );
        }
        prerequisites.add(PlanPrerequisite.met("plan-table-columns", "PLAN_TABLE has compatible columns"));

        Set<String> missingPrivileges = OraclePlanTable.missingPrivileges(context, target);
        if (!missingPrivileges.isEmpty()) {
            String message = "Missing PLAN_TABLE privileges: " + String.join(", ", missingPrivileges);
            PlanPrerequisite failure = PlanPrerequisite.unmet(
                    PlanCodes.PLAN_PERMISSION_DENIED,
                    message,
                    "Grant SELECT, INSERT, and DELETE on the resolved PLAN_TABLE"
            );
            prerequisites.add(failure);
            return unmet(context, prerequisites, PlanDiagnostic.of(failure.code(), message));
        }
        prerequisites.add(PlanPrerequisite.met("plan-table-privileges", "PLAN_TABLE SELECT/INSERT/DELETE available"));
        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT"));

        return generateReadAndCleanup(context, statement, target, transaction, prerequisites);
    }

    private DialectPlanResult generateReadAndCleanup(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            OraclePlanTable.Target target,
            TransactionCheck transaction,
            List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        String statementId = statementId();
        OraclePlanTableAccess.Capture raw = null;
        SQLException originalError = null;
        boolean generationAttempted = false;

        try {
            context.throwIfCancelled();
            generationAttempted = true;
            OraclePlanTableAccess.explain(context, statement, target, statementId);
            raw = OraclePlanTableAccess.read(context, target, statementId);
        } catch (SQLException e) {
            originalError = e;
        }

        SQLException cleanupError = null;
        if (generationAttempted) {
            try {
                OraclePlanTableAccess.delete(context, target, statementId);
            } catch (SQLException e) {
                cleanupError = e;
            }
        }

        if (cleanupError != null) {
            if (!canReuseAfterCleanupFailure(context, transaction, originalError, cleanupError)) {
                throw invalidatedAfterCleanupFailure(
                        context,
                        originalError,
                        cleanupError,
                        raw,
                        prerequisites,
                        statementId
                );
            }

            PlanDiagnostic cleanupWarning = cleanupDiagnostic(cleanupError, statementId);
            PlanEffects effects = effects(
                    PlanEffects.AuxiliaryStorage.RESIDUAL,
                    PlanEffects.ConnectionDisposition.REUSE,
                    PlanEffects.TransactionState.UNCHANGED
            );
            if (originalError != null) {
                return errorResult(context, originalError, raw, prerequisites, effects, List.of(cleanupWarning));
            }
            return resultFromRaw(context, raw, prerequisites, effects, List.of(cleanupWarning));
        }

        PlanEffects.TransactionState transactionEffect = transaction.participates()
                ? PlanEffects.TransactionState.PARTICIPATED
                : PlanEffects.TransactionState.UNCHANGED;
        PlanEffects completedEffects = effects(
                PlanEffects.AuxiliaryStorage.CLEANED,
                PlanEffects.ConnectionDisposition.REUSE,
                transactionEffect
        );
        if (originalError == null) {
            return resultFromRaw(context, raw, prerequisites, completedEffects, List.of());
        }
        if (isConnectionBroken(originalError)) {
            throw invalidated(context, originalError, null, PlanEffects.discard(
                    PlanEffects.SessionState.UNKNOWN,
                    PlanEffects.TransactionState.UNKNOWN
            ));
        }
        return errorResult(context, originalError, raw, prerequisites, completedEffects, List.of());
    }

    private DialectPlanResult resultFromRaw(
            PlanExecutionContext context,
            OraclePlanTableAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            List<PlanDiagnostic> extraWarnings
    ) {
        List<PlanDiagnostic> warnings = new ArrayList<>(extraWarnings);
        if (raw == null) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Oracle PLAN_TABLE returned no materialized result"));
            return new DialectPlanResult(
                    context.serverVersion(), PlanStatus.RAW_ONLY, List.of(), null, PlanRawFormat.TABLE,
                    RAW_FORMAT_VERSION, false, capabilities, prerequisites, effects, null, warnings
            );
        }
        if (raw.truncated()) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Oracle plan table result exceeded a display limit"));
            return new DialectPlanResult(
                    context.serverVersion(), PlanStatus.RAW_ONLY, List.of(), raw.json(), PlanRawFormat.TABLE,
                    RAW_FORMAT_VERSION, true, capabilities, prerequisites, effects, null, warnings
            );
        }

        OraclePlanParser.ParseResult parsed = OraclePlanParser.parse(raw.json(), context.maxNodes(), context.maxDepth());
        warnings.addAll(parsed.warnings());
        return new DialectPlanResult(
                context.serverVersion(),
                parsed.structured() ? PlanStatus.SUCCESS : PlanStatus.RAW_ONLY,
                parsed.roots(),
                raw.json(),
                PlanRawFormat.TABLE,
                RAW_FORMAT_VERSION,
                false,
                capabilities,
                prerequisites,
                effects,
                null,
                warnings
        );
    }

    private DialectPlanResult errorResult(
            PlanExecutionContext context,
            SQLException error,
            OraclePlanTableAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            List<PlanDiagnostic> warnings
    ) {
        PlanStatus status = isCancelled(context, error) ? PlanStatus.CANCELLED : PlanStatus.ERROR;
        String code = status == PlanStatus.CANCELLED ? PlanCodes.CANCELLED : PlanCodes.SQL_ERROR;
        return new DialectPlanResult(
                context.serverVersion(), status, List.of(), raw == null ? null : raw.json(),
                raw == null ? null : PlanRawFormat.TABLE, raw == null ? null : RAW_FORMAT_VERSION,
                raw != null && raw.truncated(), capabilities, prerequisites, effects,
                sqlDiagnostic(code, error), warnings
        );
    }

    private TransactionCheck checkTransaction(PlanExecutionContext context) throws SQLException {
        if (context.transactionProbeFailed()) {
            String detail = context.transactionProbeDetail();
            String message = detail == null || detail.isBlank()
                    ? "Oracle transaction state probe failed"
                    : "Oracle transaction state probe failed: " + detail;
            return TransactionCheck.failed(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    message,
                    "Reconnect and retry from a known transaction state"
            ));
        }

        PlanTransactionState state = context.transactionState();
        boolean autoCommit = context.connection().getAutoCommit();
        if (state == PlanTransactionState.UNKNOWN) {
            return TransactionCheck.failed(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "Oracle transaction state is unknown",
                    "Reconnect and retry from an idle auto-commit connection or an active transaction"
            ));
        }
        if (state == PlanTransactionState.TRANSACTION_FAILED) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "The current Oracle transaction is not usable",
                    "Finish the existing transaction and retry"
            ));
        }
        if (state == PlanTransactionState.MANUAL_COMMIT_IDLE) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "A manual-commit idle connection cannot start PLAN_TABLE auxiliary DML",
                    "Use an idle auto-commit connection or retry inside an already-active transaction"
            ));
        }
        if (state == PlanTransactionState.AUTO_COMMIT && !autoCommit) {
            return TransactionCheck.failed(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "Oracle transaction state conflicts with JDBC auto-commit state",
                    "Reconnect and retry from a known transaction state"
            ));
        }
        if (state == PlanTransactionState.TRANSACTION_ACTIVE && autoCommit) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "An active Oracle transaction with JDBC auto-commit enabled is unsafe for auxiliary DML",
                    "Finish the transaction and retry from a known state"
            ));
        }
        return new TransactionCheck(
                state,
                autoCommit,
                state == PlanTransactionState.TRANSACTION_ACTIVE
                        ? "PLAN_TABLE DML will participate in the active transaction"
                        : "Idle auto-commit connection",
                null
        );
    }

    private DialectPlanResult unmet(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites,
            PlanDiagnostic error
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
                PlanEffects.unchangedReuse(),
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
            String normalized = name.replace("\"", "").toLowerCase(Locale.ROOT);
            int dot = normalized.lastIndexOf('.');
            String owner = dot < 0 ? "" : normalized.substring(0, dot);
            String function = dot < 0 ? normalized : normalized.substring(dot + 1);
            if ((!owner.isEmpty() && !SAFE_SCHEMAS.contains(owner)) || !SAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is not a known side-effect-free Oracle builtin"
                );
            }
        }
        return null;
    }

    private static boolean isConnectionBroken(SQLException error) {
        String state = error.getSQLState();
        return state != null && state.startsWith("08");
    }

    private static boolean canReuseAfterCleanupFailure(
            PlanExecutionContext context,
            TransactionCheck transaction,
            SQLException originalError,
            SQLException cleanupError
    ) {
        if (transaction.participates()
                || transaction.state() != PlanTransactionState.AUTO_COMMIT
                || !transaction.autoCommit()
                || context.isCancelled()
                || isConnectionBroken(cleanupError)
                || (originalError != null && isConnectionBroken(originalError))) {
            return false;
        }
        try {
            if (!context.connection().getAutoCommit()) {
                return false;
            }
            String sql = "SELECT DBMS_TRANSACTION.LOCAL_TRANSACTION_ID(FALSE) FROM DUAL";
            try (OraclePlanJdbc.Tracked<Statement> tracked = OraclePlanJdbc.statement(context, false);
                 ResultSet resultSet = tracked.statement().executeQuery(sql)) {
                if (!resultSet.next()) {
                    return false;
                }
                String transactionId = resultSet.getString(1);
                return transactionId == null || transactionId.isBlank();
            }
        } catch (SQLException probeError) {
            cleanupError.addSuppressed(probeError);
            return false;
        }
    }

    static String validatedVersionBaseline(String version) {
        if (version == null || version.isBlank() || !ORACLE_DATABASE_PRODUCT.matcher(version).find()) {
            return null;
        }
        if (ORACLE_19C_VERSION.matcher(version).find()) {
            return ORACLE_19C_BASELINE;
        }
        if (ORACLE_26AI_PRODUCT.matcher(version).find()
                && ORACLE_26AI_23_26_VERSION.matcher(version).find()) {
            return ORACLE_26AI_23_26_BASELINE;
        }
        return null;
    }

    private static boolean isCancelled(PlanExecutionContext context, SQLException error) {
        String message = error.getMessage();
        return context.isCancelled()
                || error.getErrorCode() == 1013
                || (message != null && (message.contains("ORA-01013")
                || message.toLowerCase(Locale.ROOT).contains("cancel")));
    }

    private static String statementId() {
        byte[] bytes = new byte[10];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "CHEN_" + java.util.HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    }

    private static PlanEffects effects(
            PlanEffects.AuxiliaryStorage auxiliaryStorage,
            PlanEffects.ConnectionDisposition disposition,
            PlanEffects.TransactionState transactionState
    ) {
        return new PlanEffects(
                PlanEffects.SessionState.UNCHANGED,
                transactionState,
                auxiliaryStorage,
                disposition
        );
    }

    private static PlanDiagnostic cleanupDiagnostic(SQLException error, String statementId) {
        return new PlanDiagnostic(
                PlanCodes.AUXILIARY_CLEANUP_FAILED,
                "Failed to delete Oracle PLAN_TABLE rows for request " + statementId + ": " + error.getMessage(),
                error.getSQLState(),
                Integer.toString(error.getErrorCode())
        );
    }

    private ConnectionInvalidatedException invalidatedAfterCleanupFailure(
            PlanExecutionContext context,
            SQLException original,
            SQLException cleanup,
            OraclePlanTableAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            String statementId
    ) {
        SQLException cause = original == null ? cleanup : original;
        PlanDiagnostic originalDiagnostic = original == null
                ? cleanupDiagnostic(cleanup, statementId)
                : sqlDiagnostic(isCancelled(context, original) ? PlanCodes.CANCELLED : PlanCodes.SQL_ERROR, original);
        PlanDiagnostic cleanupDiagnostic = cleanupDiagnostic(cleanup, statementId);
        List<PlanDiagnostic> warnings = List.of(cleanupDiagnostic);
        PlanEffects partialEffects = effects(
                PlanEffects.AuxiliaryStorage.RESIDUAL,
                PlanEffects.ConnectionDisposition.DISCARD,
                PlanEffects.TransactionState.UNKNOWN
        );
        DialectPlanResult partial = new DialectPlanResult(
                context.serverVersion(), PlanStatus.CONNECTION_INVALIDATED, List.of(),
                raw == null ? null : raw.json(), raw == null ? null : PlanRawFormat.TABLE,
                raw == null ? null : RAW_FORMAT_VERSION, raw != null && raw.truncated(), capabilities,
                prerequisites, partialEffects, originalDiagnostic, warnings
        );
        return new ConnectionInvalidatedException(
                "Oracle PLAN_TABLE cleanup failed; the connection cannot be reused",
                cause,
                originalDiagnostic,
                cleanupDiagnostic,
                partial
        );
    }

    private ConnectionInvalidatedException invalidated(
            PlanExecutionContext context,
            SQLException original,
            SQLException cleanup,
            PlanEffects effects
    ) {
        PlanDiagnostic originalDiagnostic = sqlDiagnostic(PlanCodes.CONNECTION_INVALIDATED, original);
        PlanDiagnostic cleanupDiagnostic = cleanup == null
                ? null
                : sqlDiagnostic(PlanCodes.AUXILIARY_CLEANUP_FAILED, cleanup);
        DialectPlanResult partial = new DialectPlanResult(
                context.serverVersion(),
                PlanStatus.CONNECTION_INVALIDATED,
                List.of(),
                null,
                null,
                null,
                false,
                capabilities,
                List.of(),
                effects,
                originalDiagnostic,
                cleanupDiagnostic == null ? List.of() : List.of(cleanupDiagnostic)
        );
        return new ConnectionInvalidatedException(
                original.getMessage(),
                original,
                originalDiagnostic,
                cleanupDiagnostic,
                partial
        );
    }

    private static PlanDiagnostic sqlDiagnostic(String code, SQLException error) {
        return new PlanDiagnostic(
                code,
                error.getMessage(),
                error.getSQLState(),
                Integer.toString(error.getErrorCode())
        );
    }

    private record TransactionCheck(
            PlanTransactionState state,
            boolean autoCommit,
            String message,
            PlanPrerequisite failure
    ) {
        static TransactionCheck failed(PlanPrerequisite failure) {
            return new TransactionCheck(PlanTransactionState.UNKNOWN, false, null, failure);
        }

        boolean participates() {
            return state == PlanTransactionState.TRANSACTION_ACTIVE;
        }
    }

}
