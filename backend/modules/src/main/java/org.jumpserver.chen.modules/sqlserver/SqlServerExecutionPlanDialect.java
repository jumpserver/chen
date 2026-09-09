package org.jumpserver.chen.modules.sqlserver;

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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class SqlServerExecutionPlanDialect extends BaseExecutionPlanDialect {
    static final String SHOWPLAN_ON = "SET SHOWPLAN_XML ON";
    static final String SHOWPLAN_OFF = "SET SHOWPLAN_XML OFF";
    static final String STATE_PROBE =
            "SELECT CAST(431205 AS int) AS chen_showplan_state_probe, XACT_STATE() AS chen_xact_state";
    static final String PERMISSION_PROBE =
            "SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'SHOWPLAN') AS chen_has_showplan";
    private static final int MAX_PROBE_BYTES = 128 * 1024;
    private static final Pattern EXTERNAL_ROWSET = Pattern.compile(
            "(?i)\\b(?:OPENROWSET|OPENQUERY|OPENDATASOURCE)\\s*\\("
    );
    private static final Set<String> SAFE_SCHEMAS = Set.of("sys");
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
            "abs", "acos", "asin", "atan", "atn2", "avg", "ceiling", "count", "degrees",
            "exp", "floor", "log", "log10", "max", "min", "pi", "power", "radians", "rand",
            "round", "sign", "sin", "sqrt", "square", "sum", "tan", "checksum", "binary_checksum",
            "cast", "convert", "try_cast", "try_convert", "parse", "try_parse", "coalesce", "isnull",
            "nullif", "iif", "choose", "case", "current_timestamp", "getdate", "getutcdate",
            "sysdatetime", "sysutcdatetime", "sysdatetimeoffset", "dateadd", "datediff", "datediff_big",
            "datefromparts", "datetimefromparts", "datetime2fromparts", "datetimeoffsetfromparts",
            "datename", "datepart", "day", "eomonth", "isdate", "month", "smalldatetimefromparts",
            "timefromparts", "year", "char", "concat", "concat_ws", "difference", "format", "left",
            "len", "lower", "ltrim", "nchar", "patindex", "quotename", "replace", "replicate",
            "reverse", "right", "rtrim", "soundex", "space", "str", "string_agg", "string_escape",
            "string_split", "stuff", "substring", "translate", "trim", "unicode", "upper",
            "json_query", "json_value", "isjson", "json_modify", "row_number", "rank", "dense_rank",
            "ntile", "lag", "lead", "first_value", "last_value", "percent_rank", "percentile_cont",
            "percentile_disc", "cume_dist", "newid", "newsequentialid", "host_name", "db_name",
            "schema_name", "object_name", "type_name", "user_name", "suser_sname", "original_login",
            "has_perms_by_name", "xact_state", "grouping", "grouping_id"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.sqlserver();

    @Override
    public PlanDatabase database() {
        return PlanDatabase.sqlserver;
    }

    @Override
    public ExecutionPlanCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public DialectPlanResult explainEstimated(PlanExecutionContext context, SqlStatementAnalysis statement)
            throws SQLException {
        List<PlanPrerequisite> prerequisites = new ArrayList<>();
        PlanDiagnostic unsafe = unsafeStatement(statement);
        if (unsafe != null) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.UNSAFE_TO_ESTIMATE,
                    unsafe.message(),
                    "Remove user-defined functions or external rowset access and retry"
            ));
            return unmet(context, prerequisites, unsafe, PlanEffects.unchangedReuse());
        }

        addVersionAndDriverDiagnostics(context, prerequisites);
        PlanTransactionState transactionState = validateTransaction(context, prerequisites);
        if (transactionState == null) {
            PlanPrerequisite failure = prerequisites.get(prerequisites.size() - 1);
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(failure.code(), failure.message()),
                    PlanEffects.unchangedReuse()
            );
        }

        Probe initial;
        try {
            initial = probeShowplan(context, true);
        } catch (SQLException e) {
            if (isConnectionBroken(e)) {
                throw invalidated(context, e, null, null, prerequisites);
            }
            if (isCancelled(context, e)) {
                throw e;
            }
            String message = "Unable to determine the initial SHOWPLAN_XML state: " + safeMessage(e);
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.SESSION_STATE_UNSAFE,
                    message,
                    "Reconnect and retry from a known SHOWPLAN_XML OFF session"
            ));
            return unmet(context, prerequisites, sqlDiagnostic(PlanCodes.SESSION_STATE_UNSAFE, message, e),
                    PlanEffects.unchangedReuse());
        }

        if (initial.mode() == ShowplanMode.ON) {
            String message = "SHOWPLAN_XML is already enabled; Chen will not alter the existing session state";
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.SESSION_STATE_UNSAFE,
                    message,
                    "Turn SHOWPLAN_XML OFF outside Chen or reconnect, then retry"
            ));
            return unmet(context, prerequisites, PlanDiagnostic.of(PlanCodes.SESSION_STATE_UNSAFE, message),
                    PlanEffects.unchangedReuse());
        }
        if (initial.mode() != ShowplanMode.OFF || initial.xactState() == null) {
            String message = "The initial SHOWPLAN_XML or SQL Server transaction state could not be confirmed";
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.SESSION_STATE_UNSAFE,
                    message,
                    "Reconnect and retry from a known SHOWPLAN_XML OFF session"
            ));
            return unmet(context, prerequisites, PlanDiagnostic.of(PlanCodes.SESSION_STATE_UNSAFE, message),
                    PlanEffects.unchangedReuse());
        }
        if (!transactionMatches(transactionState, initial.xactState())) {
            SQLException inconsistency = new SQLException(
                    "SQL Server transaction probes disagree before SHOWPLAN_XML was changed", "HY000"
            );
            throw invalidated(context, inconsistency, null, null, prerequisites);
        }
        prerequisites.add(PlanPrerequisite.met("showplan-state", "Initial SHOWPLAN_XML state is OFF"));
        prerequisites.add(PlanPrerequisite.met("transaction", transactionDescription(transactionState)));

        Permission permission;
        try {
            permission = probePermission(context);
        } catch (SQLException e) {
            if (isConnectionBroken(e)) {
                throw invalidated(context, e, null, null, prerequisites);
            }
            if (isCancelled(context, e)) {
                throw e;
            }
            String message = "Unable to verify SHOWPLAN permission in the current database: " + safeMessage(e);
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.PLAN_PERMISSION_DENIED,
                    message,
                    "Grant SHOWPLAN in every referenced database and reconnect"
            ));
            return unmet(context, prerequisites, sqlDiagnostic(PlanCodes.PLAN_PERMISSION_DENIED, message, e),
                    PlanEffects.unchangedReuse());
        }
        if (permission != Permission.GRANTED) {
            String message = permission == Permission.DENIED
                    ? "SHOWPLAN permission is missing in the current database"
                    : "SHOWPLAN permission in the current database could not be confirmed";
            PlanPrerequisite prerequisite = permission == Permission.DENIED
                    ? PlanPrerequisite.unmet(
                    PlanCodes.PLAN_PERMISSION_DENIED,
                    message,
                    "Grant SHOWPLAN in every database referenced by the query"
            )
                    : PlanPrerequisite.unknown(
                    PlanCodes.PLAN_PERMISSION_DENIED,
                    message,
                    "Grant SHOWPLAN in every database referenced by the query and reconnect"
            );
            prerequisites.add(prerequisite);
            return unmet(context, prerequisites, PlanDiagnostic.of(PlanCodes.PLAN_PERMISSION_DENIED, message),
                    PlanEffects.unchangedReuse());
        }
        prerequisites.add(PlanPrerequisite.met(
                "showplan-permission",
                "Current database SHOWPLAN permission is available; referenced databases are verified during compilation"
        ));
        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT"));

        return executeShowplan(context, statement, transactionState, prerequisites);
    }

    private DialectPlanResult executeShowplan(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            PlanTransactionState initialTransaction,
            List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        boolean onAttempted = false;
        boolean onConfirmed = false;
        String failureStage = "SET SHOWPLAN_XML ON failed: ";
        SQLException originalError = null;
        SqlServerPlanJdbc.PlanCapture capture = null;

        try {
            try (SqlServerPlanJdbc.TrackedStatement on = SqlServerPlanJdbc.requestStatement(context)) {
                onAttempted = true;
                SqlServerPlanJdbc.executeAndDrain(on.statement(), SHOWPLAN_ON);
                onConfirmed = true;
            }
            failureStage = "SQL Server plan compilation or result read failed: ";
            context.throwIfCancelled();
            try (SqlServerPlanJdbc.TrackedStatement query = SqlServerPlanJdbc.requestStatement(context)) {
                capture = SqlServerPlanJdbc.executePlan(
                        query.statement(),
                        statement.sql(),
                        context.maxRawBytes()
                );
            }
        } catch (SQLException e) {
            originalError = e;
        }

        SQLException restoreError = null;
        Probe restored = null;
        if (onAttempted) {
            try {
                restored = restoreAndVerify(context);
                if (restored.mode() != ShowplanMode.OFF || restored.xactState() == null) {
                    restoreError = new SQLException("SHOWPLAN_XML OFF could not be verified", "HY000");
                } else if (!transactionRestored(initialTransaction, restored.xactState())) {
                    restoreError = new SQLException(
                            "SQL Server transaction state changed while restoring SHOWPLAN_XML", "HY000"
                    );
                }
            } catch (SQLException e) {
                restoreError = e;
            }
        }

        if (restoreError != null) {
            throw invalidated(context, originalError, restoreError, capture, prerequisites);
        }
        if (originalError != null && isConnectionBroken(originalError)) {
            throw invalidated(context, originalError, null, capture, prerequisites);
        }

        PlanEffects.TransactionState transactionEffect = restored != null && restored.xactState() == -1
                ? PlanEffects.TransactionState.FAILED
                : PlanEffects.TransactionState.UNCHANGED;
        PlanEffects effects = new PlanEffects(
                onAttempted ? PlanEffects.SessionState.RESTORED : PlanEffects.SessionState.UNCHANGED,
                transactionEffect,
                PlanEffects.AuxiliaryStorage.NONE,
                PlanEffects.ConnectionDisposition.REUSE
        );

        if (originalError != null) {
            return errorResult(context, originalError, capture, prerequisites, effects, failureStage);
        }
        if (!onConfirmed) {
            SQLException error = new SQLException("SHOWPLAN_XML ON was not confirmed", "HY000");
            return errorResult(context, error, capture, prerequisites, effects, "SET SHOWPLAN_XML ON failed: ");
        }
        if (transactionEffect == PlanEffects.TransactionState.FAILED) {
            SQLException error = new SQLException(
                    "The active SQL Server transaction became uncommittable while compiling the plan", "25000"
            );
            return errorResult(
                    context,
                    error,
                    capture,
                    prerequisites,
                    effects,
                    "SQL Server plan compilation changed the transaction state: "
            );
        }
        return resultFromCapture(context, capture, prerequisites, effects);
    }

    private Probe restoreAndVerify(PlanExecutionContext context) throws SQLException {
        try (SqlServerPlanJdbc.TrackedStatement off = SqlServerPlanJdbc.cleanupStatement(context)) {
            SqlServerPlanJdbc.executeAndDrain(off.statement(), SHOWPLAN_OFF);
        }
        return probeShowplan(context, false);
    }

    private Probe probeShowplan(PlanExecutionContext context, boolean requestDeadline) throws SQLException {
        SqlServerPlanJdbc.ProbeCapture capture;
        if (requestDeadline) {
            try (SqlServerPlanJdbc.TrackedStatement statement = SqlServerPlanJdbc.requestStatement(context)) {
                capture = SqlServerPlanJdbc.executeProbe(statement.statement(), STATE_PROBE, MAX_PROBE_BYTES);
            }
        } else {
            try (SqlServerPlanJdbc.TrackedStatement statement = SqlServerPlanJdbc.cleanupStatement(context)) {
                capture = SqlServerPlanJdbc.executeProbe(statement.statement(), STATE_PROBE, MAX_PROBE_BYTES);
            }
        }
        if (capture.truncated() || capture.rows().size() != 1 || capture.rows().get(0).isEmpty()) {
            return new Probe(ShowplanMode.UNKNOWN, null);
        }
        List<String> row = capture.rows().get(0);
        String first = row.get(0);
        if (SqlServerPlanParser.isShowPlanXml(first, MAX_PROBE_BYTES)) {
            return new Probe(ShowplanMode.ON, null);
        }
        if (!"431205".equals(first) || row.size() < 2) {
            return new Probe(ShowplanMode.UNKNOWN, null);
        }
        try {
            return new Probe(ShowplanMode.OFF, Integer.parseInt(row.get(1)));
        } catch (NumberFormatException e) {
            return new Probe(ShowplanMode.OFF, null);
        }
    }

    private Permission probePermission(PlanExecutionContext context) throws SQLException {
        SqlServerPlanJdbc.ProbeCapture capture;
        try (SqlServerPlanJdbc.TrackedStatement statement = SqlServerPlanJdbc.requestStatement(context)) {
            capture = SqlServerPlanJdbc.executeProbe(statement.statement(), PERMISSION_PROBE, 128);
        }
        if (capture.truncated() || capture.rows().size() != 1 || capture.rows().get(0).isEmpty()) {
            return Permission.UNKNOWN;
        }
        return switch (capture.rows().get(0).get(0).toLowerCase(Locale.ROOT)) {
            case "1", "true" -> Permission.GRANTED;
            case "0", "false" -> Permission.DENIED;
            default -> Permission.UNKNOWN;
        };
    }

    private DialectPlanResult resultFromCapture(
            PlanExecutionContext context,
            SqlServerPlanJdbc.PlanCapture capture,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects
    ) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (capture == null || capture.documents().isEmpty()) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "SQL Server returned no Showplan XML document"));
            return new DialectPlanResult(
                    context.serverVersion(), PlanStatus.RAW_ONLY, List.of(), capture == null ? null : capture.rawText(),
                    PlanRawFormat.XML, SqlServerPlanParser.RAW_FORMAT_VERSION,
                    capture != null && capture.truncated(), capabilities,
                    prerequisites, effects, null, warnings
            );
        }
        if (capture.documents().size() > 1) {
            warnings.add(PlanDiagnostic.of(
                    "MULTIPLE_SHOWPLAN_DOCUMENTS",
                    "SQL Server returned multiple Showplan XML documents; all were normalized in result order"
            ));
        }
        if (capture.truncated()) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "SQL Server Showplan XML exceeded a capture limit"));
            return new DialectPlanResult(
                    context.serverVersion(), PlanStatus.RAW_ONLY, List.of(), capture.rawText(), PlanRawFormat.XML,
                    SqlServerPlanParser.RAW_FORMAT_VERSION, true, capabilities, prerequisites, effects, null, warnings
            );
        }

        SqlServerPlanParser.ParseResult parsed = SqlServerPlanParser.parse(
                capture.documents(),
                context.maxRawBytes(),
                context.maxNodes(),
                context.maxDepth()
        );
        warnings.addAll(parsed.warnings());
        return new DialectPlanResult(
                context.serverVersion(),
                parsed.structured() ? PlanStatus.SUCCESS : PlanStatus.RAW_ONLY,
                parsed.roots(),
                capture.rawText(),
                PlanRawFormat.XML,
                SqlServerPlanParser.RAW_FORMAT_VERSION,
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
            SqlServerPlanJdbc.PlanCapture capture,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            String stage
    ) {
        PlanStatus status;
        String code;
        if (isCancelled(context, error)) {
            status = PlanStatus.CANCELLED;
            code = PlanCodes.CANCELLED;
        } else if (isPermissionDenied(error)) {
            status = PlanStatus.PREREQUISITES_UNMET;
            code = PlanCodes.PLAN_PERMISSION_DENIED;
            prerequisites.add(PlanPrerequisite.unmet(
                    code,
                    safeMessage(error),
                    "Grant query permissions and SHOWPLAN in every database referenced by the SELECT"
            ));
        } else {
            status = PlanStatus.ERROR;
            code = PlanCodes.SQL_ERROR;
        }
        return new DialectPlanResult(
                context.serverVersion(), status, List.of(), capture == null ? null : capture.rawText(),
                capture == null ? null : PlanRawFormat.XML,
                capture == null ? null : SqlServerPlanParser.RAW_FORMAT_VERSION,
                capture != null && capture.truncated(), capabilities, prerequisites, effects,
                sqlDiagnostic(code, stage + safeMessage(error), error), List.of()
        );
    }

    private ConnectionInvalidatedException invalidated(
            PlanExecutionContext context,
            SQLException original,
            SQLException cleanup,
            SqlServerPlanJdbc.PlanCapture capture,
            List<PlanPrerequisite> prerequisites
    ) {
        PlanDiagnostic originalDiagnostic = original == null ? null : sqlDiagnostic(
                isCancelled(context, original) ? PlanCodes.CANCELLED : PlanCodes.SQL_ERROR,
                safeMessage(original),
                original
        );
        PlanDiagnostic cleanupDiagnostic = cleanup == null ? null : sqlDiagnostic(
                PlanCodes.SESSION_RESTORE_FAILED,
                "SHOWPLAN_XML session recovery could not be confirmed: " + safeMessage(cleanup),
                cleanup
        );
        PlanDiagnostic error = originalDiagnostic != null
                ? originalDiagnostic
                : (cleanupDiagnostic == null
                ? PlanDiagnostic.of(PlanCodes.CONNECTION_INVALIDATED, "SQL Server connection is not reusable")
                : cleanupDiagnostic);
        List<PlanDiagnostic> warnings = cleanupDiagnostic == null ? List.of() : List.of(cleanupDiagnostic);
        DialectPlanResult partial = new DialectPlanResult(
                context.serverVersion(), PlanStatus.CONNECTION_INVALIDATED, List.of(),
                capture == null ? null : capture.rawText(), capture == null ? null : PlanRawFormat.XML,
                capture == null ? null : SqlServerPlanParser.RAW_FORMAT_VERSION,
                capture != null && capture.truncated(), capabilities, prerequisites,
                PlanEffects.discard(PlanEffects.SessionState.UNKNOWN, PlanEffects.TransactionState.UNKNOWN),
                error, warnings
        );
        SQLException cause = cleanup != null ? cleanup : original;
        return new ConnectionInvalidatedException(
                "SQL Server SHOWPLAN_XML session could not be safely reused",
                cause,
                originalDiagnostic,
                cleanupDiagnostic,
                partial
        );
    }

    private PlanTransactionState validateTransaction(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites
    ) {
        if (context.transactionProbeFailed()) {
            String detail = context.transactionProbeDetail();
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    detail == null || detail.isBlank()
                            ? "SQL Server transaction state probe failed"
                            : "SQL Server transaction state probe failed: " + detail,
                    "Reconnect and retry from a known transaction state"
            ));
            return null;
        }
        PlanTransactionState state = context.transactionState();
        if (state == PlanTransactionState.AUTO_COMMIT || state == PlanTransactionState.TRANSACTION_ACTIVE) {
            return state;
        }
        String message = switch (state) {
            case MANUAL_COMMIT_IDLE ->
                    "A manual-commit idle connection is not probed because the probe could start a transaction";
            case TRANSACTION_FAILED -> "The current SQL Server transaction is already uncommittable";
            default -> "The current SQL Server transaction state is unknown";
        };
        prerequisites.add(state == PlanTransactionState.UNKNOWN
                ? PlanPrerequisite.unknown(
                PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message, "Reconnect and retry from a known transaction state")
                : PlanPrerequisite.unmet(
                PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message, "Finish the transaction or reconnect, then retry"));
        return null;
    }

    private void addVersionAndDriverDiagnostics(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites
    ) {
        String version = context.serverVersion();
        if (version == null || version.isBlank()) {
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED,
                    "SQL Server version could not be determined; runtime SHOWPLAN checks will be used",
                    "Record the SQL Server product version for compatibility diagnostics"
            ));
        } else {
            prerequisites.add(PlanPrerequisite.met("server-version", version));
        }
        try {
            DatabaseMetaData metadata = context.connection().getMetaData();
            String driver = metadata == null ? null : metadata.getDriverVersion();
            if (driver == null || driver.isBlank()) {
                prerequisites.add(PlanPrerequisite.unknown(
                        PlanCodes.VERSION_NOT_VALIDATED,
                        "SQL Server JDBC driver version could not be determined",
                        "Record the JDBC driver version for compatibility diagnostics"
                ));
            } else {
                prerequisites.add(PlanPrerequisite.met("jdbc-driver-version", driver));
            }
        } catch (SQLException e) {
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED,
                    "SQL Server JDBC driver version probe failed: " + safeMessage(e),
                    "Record the JDBC driver version for compatibility diagnostics"
            ));
        }
    }

    static PlanDiagnostic unsafeStatement(SqlStatementAnalysis statement) {
        if (EXTERNAL_ROWSET.matcher(statement.sql()).find()) {
            return PlanDiagnostic.of(
                    PlanCodes.UNSAFE_TO_ESTIMATE,
                    "External SQL Server rowset providers are not safe to estimate"
            );
        }
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isBlank()) {
                continue;
            }
            String normalized = name.replace("[", "").replace("]", "").replace("\"", "")
                    .toLowerCase(Locale.ROOT);
            int dot = normalized.lastIndexOf('.');
            String schema = dot < 0 ? "" : normalized.substring(0, dot);
            String function = dot < 0 ? normalized : normalized.substring(dot + 1);
            if ((!schema.isEmpty() && !SAFE_SCHEMAS.contains(schema)) || !SAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is not a known SQL Server builtin safe for estimated plans"
                );
            }
        }
        return null;
    }

    private DialectPlanResult unmet(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites,
            PlanDiagnostic error,
            PlanEffects effects
    ) {
        return new DialectPlanResult(
                context.serverVersion(), PlanStatus.PREREQUISITES_UNMET, List.of(), null, null, null,
                false, capabilities, prerequisites, effects, error, List.of()
        );
    }

    private static boolean transactionMatches(PlanTransactionState expected, int actual) {
        return (expected == PlanTransactionState.AUTO_COMMIT && actual == 0)
                || (expected == PlanTransactionState.TRANSACTION_ACTIVE && actual == 1);
    }

    private static boolean transactionRestored(PlanTransactionState expected, int actual) {
        if (expected == PlanTransactionState.AUTO_COMMIT) {
            return actual == 0;
        }
        return expected == PlanTransactionState.TRANSACTION_ACTIVE && (actual == 1 || actual == -1);
    }

    private static String transactionDescription(PlanTransactionState state) {
        return state == PlanTransactionState.TRANSACTION_ACTIVE
                ? "Existing active transaction will not be committed or rolled back"
                : "Idle auto-commit session";
    }

    private static boolean isPermissionDenied(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 229 || current.getErrorCode() == 262) {
                return true;
            }
            String message = safeMessage(current).toLowerCase(Locale.ROOT);
            if (message.contains("permission")
                    && (message.contains("showplan") || message.contains("select") || message.contains("denied"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCancelled(PlanExecutionContext context, SQLException error) {
        if (context.isCancelled()) {
            return true;
        }
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String message = safeMessage(current).toLowerCase(Locale.ROOT);
            if ("57014".equals(current.getSQLState())
                    || message.contains("cancel")
                    || message.contains("timed out")
                    || message.contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionBroken(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    private static PlanDiagnostic sqlDiagnostic(String code, String message, SQLException error) {
        return new PlanDiagnostic(code, message, error.getSQLState(), Integer.toString(error.getErrorCode()));
    }

    private static String safeMessage(SQLException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private enum ShowplanMode {
        ON,
        OFF,
        UNKNOWN
    }

    private enum Permission {
        GRANTED,
        DENIED,
        UNKNOWN
    }

    private record Probe(ShowplanMode mode, Integer xactState) {
    }
}
