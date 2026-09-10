package org.jumpserver.chen.modules.db2;

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

import java.security.SecureRandom;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Db2ExecutionPlanDialect extends BaseExecutionPlanDialect {
    static final String RAW_FORMAT_VERSION = "chen-table-v1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SAFE_SCHEMAS = Set.of(
            "sysibm", "sysfun", "sysproc", "sysibmadm"
    );
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
            "abs", "acos", "asin", "atan", "atan2", "avg", "bigint", "ceil", "ceiling",
            "char", "coalesce", "concat", "cos", "count", "date", "day", "days", "decimal",
            "decode", "degrees", "dense_rank", "double", "exp", "extract", "first_value",
            "float", "floor", "greatest", "hex", "hour", "integer", "lag", "last_day",
            "last_value", "lead", "least", "length", "ln", "locate", "log", "log10", "lower",
            "lpad", "ltrim", "max", "microsecond", "midnight_seconds", "min", "minute", "mod",
            "month", "months_between", "nullif", "nth_value", "ntile", "power", "quarter",
            "radians", "rank", "regexp_count", "regexp_instr", "regexp_like", "regexp_replace",
            "regexp_substr", "replace", "round", "row_number", "rpad", "rtrim", "second",
            "sign", "sin", "smallint", "sqrt", "stddev", "stddev_samp", "substring", "sum",
            "tan", "time", "timestamp", "translate", "trim", "trunc", "truncate", "upper",
            "variance", "var_samp", "varchar", "week", "year"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.db2();

    @Override
    public PlanDatabase database() {
        return PlanDatabase.db2;
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
                throw invalidated(context, e, null, null, List.of(), effects(
                        PlanEffects.AuxiliaryStorage.UNKNOWN,
                        PlanEffects.TransactionState.UNKNOWN,
                        PlanEffects.ConnectionDisposition.DISCARD
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
            return unmet(context.serverVersion(), prerequisites, unsafe);
        }

        TransactionCheck transaction = checkTransaction(context);
        if (transaction.failure() != null) {
            prerequisites.add(transaction.failure());
            return unmet(
                    context.serverVersion(),
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, transaction.failure().message())
            );
        }
        prerequisites.add(PlanPrerequisite.met("transaction", transaction.message()));

        ServerIdentity server;
        try {
            server = serverIdentity(context);
        } catch (SQLException e) {
            if (isCancelled(context, e) || isConnectionBroken(e)) {
                throw e;
            }
            PlanDiagnostic diagnostic = sqlDiagnostic(
                    PlanCodes.UNSUPPORTED_DATABASE,
                    "The connected server is not a verified DB2 LUW target: " + message(e),
                    e
            );
            prerequisites.add(PlanPrerequisite.unmet(
                    diagnostic.code(), diagnostic.message(),
                    "Connect to DB2 for Linux, UNIX, and Windows"
            ));
            return unmet(context.serverVersion(), prerequisites, diagnostic);
        }
        prerequisites.add(PlanPrerequisite.met(
                "db2-luw",
                server.productName() + " " + server.productVersion() + "; service " + server.serviceLevel()
        ));
        prerequisites.add(PlanPrerequisite.met(
                "jdbc-driver",
                server.driverName() + " " + server.driverVersion()
        ));

        Db2ExplainTables.Resolution resolution;
        try {
            resolution = Db2ExplainTables.resolve(context);
        } catch (SQLException e) {
            if (isCancelled(context, e) || isConnectionBroken(e)) {
                throw e;
            }
            String code = classifyTableError(e);
            PlanDiagnostic diagnostic = sqlDiagnostic(code, message(e), e);
            prerequisites.add(PlanPrerequisite.unmet(code, diagnostic.message(), remediation(code)));
            return unmet(server.displayVersion(), prerequisites, diagnostic);
        }
        if (resolution.failure() != null) {
            prerequisites.add(resolution.failure());
            return unmet(
                    server.displayVersion(),
                    prerequisites,
                    PlanDiagnostic.of(resolution.failure().code(), resolution.failure().message())
            );
        }
        Db2ExplainTables.Target target = resolution.target();
        prerequisites.add(PlanPrerequisite.met(
                "explain-table-schema",
                "Authorization ID " + target.identity().authorizationId()
                        + ", CURRENT SCHEMA " + target.identity().currentSchema()
                        + ", using complete Explain table set in " + target.schema()
        ));
        prerequisites.add(PlanPrerequisite.met(
                "explain-table-columns",
                "DB2 Explain tables have compatible request keys and plan columns"
        ));
        prerequisites.add(PlanPrerequisite.met(
                "explain-table-privileges",
                "SELECT, INSERT, and DELETE privileges available for the resolved Explain table set"
        ));
        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT"));

        return generateReadAndCleanup(
                context, statement, server.displayVersion(), target, transaction, prerequisites
        );
    }

    private DialectPlanResult generateReadAndCleanup(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            String serverVersion,
            Db2ExplainTables.Target target,
            TransactionCheck transaction,
            List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        int queryNo = positiveQueryNo();
        String queryTag = queryTag();
        String requestIdentity = "QUERYNO=" + queryNo + ", QUERYTAG=" + queryTag;
        Timestamp windowStart = Db2PlanAccess.currentTimestamp(context, true);
        Timestamp windowEnd = null;
        Db2PlanAccess.LocateResult located = null;
        Db2PlanAccess.Capture raw = null;
        SQLException originalError = null;
        boolean explainSucceeded = false;

        try {
            context.throwIfCancelled();
            Db2PlanAccess.explain(context, statement, queryNo, queryTag);
            explainSucceeded = true;
        } catch (SQLException e) {
            originalError = e;
        }

        try {
            windowEnd = Db2PlanAccess.currentTimestamp(context, false);
            located = Db2PlanAccess.locate(
                    context, target, queryNo, queryTag, windowStart, windowEnd
            );
            if (located.keys().size() == 1 && located.complete() && explainSucceeded) {
                raw = Db2PlanAccess.read(context, target, located);
            } else {
                raw = new Db2PlanAccess.Capture(
                        materializedLocator(located, context.maxRawBytes()),
                        located.statement().truncated()
                );
            }
        } catch (SQLException e) {
            originalError = accumulate(originalError, e);
        }

        PlanDiagnostic identityError = null;
        if (located != null && explainSucceeded) {
            if (located.keys().isEmpty()) {
                identityError = PlanDiagnostic.of(
                        PlanCodes.PLAN_REQUEST_NOT_FOUND,
                        "DB2 EXPLAIN_STATEMENT contains no row for " + requestIdentity
                                + " in request window " + windowStart + ".." + windowEnd
                );
            } else if (located.keys().size() > 1 || !located.complete()) {
                identityError = PlanDiagnostic.of(
                        PlanCodes.PLAN_REQUEST_AMBIGUOUS,
                        "DB2 EXPLAIN_STATEMENT does not contain one complete, unique plan request for "
                                + requestIdentity
                                + " in request window; no ambiguous or incomplete request was deleted"
                );
            }
        }

        SQLException cleanupError = null;
        Db2PlanAccess.StatementKey key = located != null && located.keys().size() == 1
                ? located.keys().get(0)
                : null;
        List<Db2PlanAccess.StatementKey> cleanupKeys = located != null && located.complete()
                ? located.requestKeys()
                : List.of();
        for (Db2PlanAccess.StatementKey cleanupKey : cleanupKeys) {
            try {
                Db2PlanAccess.cleanup(context, target, cleanupKey);
            } catch (SQLException e) {
                cleanupError = accumulate(cleanupError, e);
            }
        }

        boolean ambiguousResidual = explainSucceeded
                && (located == null || located.keys().size() != 1 || !located.complete());
        if (cleanupError != null || ambiguousResidual) {
            PlanDiagnostic cleanupDiagnostic = cleanupError == null
                    ? residualDiagnostic(identityError, requestIdentity, windowStart, windowEnd, located)
                    : cleanupDiagnostic(cleanupError, requestIdentity, key);
            if (!canReuseWithResidual(context, transaction, originalError, cleanupError)) {
                throw invalidated(
                        context,
                        originalError,
                        cleanupError,
                        raw,
                        prerequisites,
                        effects(
                                PlanEffects.AuxiliaryStorage.RESIDUAL,
                                PlanEffects.TransactionState.UNKNOWN,
                                PlanEffects.ConnectionDisposition.DISCARD
                        ),
                        cleanupDiagnostic,
                        identityError
                );
            }
            PlanEffects residualEffects = effects(
                    PlanEffects.AuxiliaryStorage.RESIDUAL,
                    transactionEffect(transaction, !cleanupKeys.isEmpty()),
                    PlanEffects.ConnectionDisposition.REUSE
            );
            if (identityError != null) {
                return diagnosticResult(
                        serverVersion, PlanStatus.ERROR, raw, prerequisites, residualEffects,
                        identityError, List.of(cleanupDiagnostic)
                );
            }
            if (originalError != null) {
                return errorResult(
                        context, serverVersion, originalError, raw, prerequisites,
                        residualEffects, List.of(cleanupDiagnostic)
                );
            }
            return resultFromRaw(
                    serverVersion, context, raw, prerequisites, residualEffects,
                    List.of(cleanupDiagnostic)
            );
        }

        PlanEffects completedEffects = effects(
                cleanupKeys.isEmpty() ? PlanEffects.AuxiliaryStorage.NONE : PlanEffects.AuxiliaryStorage.CLEANED,
                transactionEffect(transaction, !cleanupKeys.isEmpty()),
                PlanEffects.ConnectionDisposition.REUSE
        );
        if (identityError != null) {
            return diagnosticResult(
                    serverVersion, PlanStatus.ERROR, raw, prerequisites, completedEffects,
                    identityError, List.of()
            );
        }
        if (originalError != null) {
            if (isConnectionBroken(originalError) || isTransactionRollback(originalError)) {
                throw invalidated(
                        context, originalError, null, raw, prerequisites,
                        effects(
                                cleanupKeys.isEmpty()
                                        ? PlanEffects.AuxiliaryStorage.UNKNOWN
                                        : PlanEffects.AuxiliaryStorage.CLEANED,
                                PlanEffects.TransactionState.FAILED,
                                PlanEffects.ConnectionDisposition.DISCARD
                        )
                );
            }
            return errorResult(
                    context, serverVersion, originalError, raw, prerequisites, completedEffects, List.of()
            );
        }
        return resultFromRaw(serverVersion, context, raw, prerequisites, completedEffects, List.of());
    }

    private DialectPlanResult resultFromRaw(
            String serverVersion,
            PlanExecutionContext context,
            Db2PlanAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            List<PlanDiagnostic> extraWarnings
    ) {
        List<PlanDiagnostic> warnings = new ArrayList<>(extraWarnings);
        if (raw == null) {
            warnings.add(PlanDiagnostic.of(
                    PlanCodes.PLAN_PARSE_FAILED,
                    "DB2 Explain tables returned no materialized result"
            ));
            return new DialectPlanResult(
                    serverVersion, PlanStatus.RAW_ONLY, List.of(), null, PlanRawFormat.TABLE,
                    RAW_FORMAT_VERSION, false, capabilities, prerequisites, effects, null, warnings
            );
        }
        if (raw.truncated()) {
            warnings.add(PlanDiagnostic.of(
                    PlanCodes.PLAN_LIMIT_REACHED,
                    "DB2 Explain table result exceeded a display limit"
            ));
            return new DialectPlanResult(
                    serverVersion, PlanStatus.RAW_ONLY, List.of(), raw.json(), PlanRawFormat.TABLE,
                    RAW_FORMAT_VERSION, true, capabilities, prerequisites, effects, null, warnings
            );
        }
        Db2PlanParser.ParseResult parsed = Db2PlanParser.parse(
                raw.json(), context.maxNodes(), context.maxDepth()
        );
        warnings.addAll(parsed.warnings());
        return new DialectPlanResult(
                serverVersion,
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
            String serverVersion,
            SQLException error,
            Db2PlanAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            List<PlanDiagnostic> warnings
    ) {
        PlanStatus status = isCancelled(context, error) ? PlanStatus.CANCELLED : PlanStatus.ERROR;
        String code = status == PlanStatus.CANCELLED ? PlanCodes.CANCELLED : classifyTableError(error);
        if (PlanCodes.SQL_ERROR.equals(code) && isTransactionRollback(error)) {
            code = PlanCodes.TRANSACTION_CONTEXT_UNSAFE;
        }
        return diagnosticResult(
                serverVersion, status, raw, prerequisites, effects,
                sqlDiagnostic(code, message(error), error), warnings
        );
    }

    private DialectPlanResult diagnosticResult(
            String serverVersion,
            PlanStatus status,
            Db2PlanAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            PlanDiagnostic error,
            List<PlanDiagnostic> warnings
    ) {
        return new DialectPlanResult(
                serverVersion, status, List.of(), raw == null ? null : raw.json(),
                raw == null ? null : PlanRawFormat.TABLE,
                raw == null ? null : RAW_FORMAT_VERSION,
                raw != null && raw.truncated(), capabilities, prerequisites, effects, error, warnings
        );
    }

    private TransactionCheck checkTransaction(PlanExecutionContext context) throws SQLException {
        if (context.transactionProbeFailed()) {
            String detail = context.transactionProbeDetail();
            String message = detail == null || detail.isBlank()
                    ? "DB2 transaction state probe failed"
                    : "DB2 transaction state probe failed: " + detail;
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
                    "DB2 transaction state is unknown",
                    "Reconnect and retry from an idle auto-commit connection or an active transaction"
            ));
        }
        if (state == PlanTransactionState.TRANSACTION_FAILED) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "The current DB2 transaction is not usable",
                    "Finish the existing transaction and retry"
            ));
        }
        if (state == PlanTransactionState.MANUAL_COMMIT_IDLE) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "A manual-commit idle connection cannot start DB2 Explain auxiliary DML",
                    "Use an idle auto-commit connection or retry inside an already-active transaction"
            ));
        }
        if (state == PlanTransactionState.AUTO_COMMIT && !autoCommit) {
            return TransactionCheck.failed(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "DB2 transaction state conflicts with JDBC auto-commit state",
                    "Reconnect and retry from a known transaction state"
            ));
        }
        if (state == PlanTransactionState.TRANSACTION_ACTIVE && autoCommit) {
            return TransactionCheck.failed(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "An active DB2 unit of work with JDBC auto-commit enabled is unsafe for auxiliary DML",
                    "Finish the transaction and retry from a known state"
            ));
        }
        return new TransactionCheck(
                state,
                autoCommit,
                state == PlanTransactionState.TRANSACTION_ACTIVE
                        ? "DB2 Explain DML will participate in the active unit of work"
                        : "Idle auto-commit connection",
                null
        );
    }

    private static ServerIdentity serverIdentity(PlanExecutionContext context) throws SQLException {
        DatabaseMetaData metadata = context.connection().getMetaData();
        String serviceLevel;
        try (Db2PlanJdbc.Tracked<Statement> tracked = Db2PlanJdbc.statement(context, true);
             ResultSet resultSet = tracked.statement().executeQuery(
                     "SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO FETCH FIRST 1 ROW ONLY"
             )) {
            if (!resultSet.next()) {
                throw new SQLException("SYSIBMADM.ENV_INST_INFO returned no DB2 LUW service level");
            }
            serviceLevel = resultSet.getString(1);
        }
        return new ServerIdentity(
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                serviceLevel,
                metadata.getDriverName(),
                metadata.getDriverVersion()
        );
    }

    static PlanDiagnostic unsafeFunctions(SqlStatementAnalysis statement) {
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) continue;
            String normalized = name.replace("\"", "").toLowerCase(Locale.ROOT);
            int dot = normalized.lastIndexOf('.');
            String owner = dot < 0 ? "" : normalized.substring(0, dot);
            String function = dot < 0 ? normalized : normalized.substring(dot + 1);
            if ((!owner.isEmpty() && !SAFE_SCHEMAS.contains(owner)) || !SAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(
                        PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + name + " is not a known side-effect-free DB2 LUW builtin"
                );
            }
        }
        return null;
    }

    private DialectPlanResult unmet(
            String serverVersion,
            List<PlanPrerequisite> prerequisites,
            PlanDiagnostic error
    ) {
        return new DialectPlanResult(
                serverVersion, PlanStatus.PREREQUISITES_UNMET, List.of(), null, null, null,
                false, capabilities, prerequisites, PlanEffects.unchangedReuse(), error, List.of()
        );
    }

    private static boolean canReuseWithResidual(
            PlanExecutionContext context,
            TransactionCheck transaction,
            SQLException original,
            SQLException cleanup
    ) {
        if (transaction.participates() || transaction.state() != PlanTransactionState.AUTO_COMMIT
                || !transaction.autoCommit() || context.isCancelled()
                || isConnectionBroken(original) || isConnectionBroken(cleanup)
                || isTransactionRollback(original) || isTransactionRollback(cleanup)) {
            return false;
        }
        try {
            return context.connection().getAutoCommit() && context.connection().isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private ConnectionInvalidatedException invalidated(
            PlanExecutionContext context,
            SQLException original,
            SQLException cleanup,
            Db2PlanAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            PlanDiagnostic... extraWarnings
    ) {
        PlanDiagnostic originalDiagnostic = original == null
                ? null
                : sqlDiagnostic(
                isCancelled(context, original) ? PlanCodes.CANCELLED : classifyTableError(original),
                message(original), original
        );
        PlanDiagnostic cleanupDiagnostic = cleanup == null
                ? null
                : cleanupDiagnostic(cleanup, "unknown request", null);
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (cleanupDiagnostic != null) warnings.add(cleanupDiagnostic);
        if (extraWarnings != null) {
            for (PlanDiagnostic warning : extraWarnings) {
                if (warning != null) warnings.add(warning);
            }
        }
        PlanDiagnostic primary = originalDiagnostic;
        if (primary == null && extraWarnings != null) {
            for (PlanDiagnostic diagnostic : extraWarnings) {
                if (diagnostic != null && !PlanCodes.AUXILIARY_CLEANUP_FAILED.equals(diagnostic.code())) {
                    primary = diagnostic;
                    break;
                }
            }
        }
        if (primary == null) {
            primary = cleanupDiagnostic == null
                    ? PlanDiagnostic.of(PlanCodes.CONNECTION_INVALIDATED, "DB2 connection cannot be reused")
                    : cleanupDiagnostic;
        }
        DialectPlanResult partial = diagnosticResult(
                context.serverVersion(), PlanStatus.CONNECTION_INVALIDATED, raw, prerequisites,
                effects, primary, warnings
        );
        Throwable cause = original != null ? original : cleanup;
        return new ConnectionInvalidatedException(
                primary.message(),
                cause,
                primary,
                cleanupDiagnostic,
                partial
        );
    }

    private ConnectionInvalidatedException invalidated(
            PlanExecutionContext context,
            SQLException original,
            SQLException cleanup,
            Db2PlanAccess.Capture raw,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects
    ) {
        return invalidated(context, original, cleanup, raw, prerequisites, effects, new PlanDiagnostic[0]);
    }

    private static PlanDiagnostic residualDiagnostic(
            PlanDiagnostic identityError,
            String requestIdentity,
            Timestamp start,
            Timestamp end,
            Db2PlanAccess.LocateResult located
    ) {
        String candidates = located == null
                ? "locator unavailable"
                : located.keys().isEmpty()
                ? "no full statement key"
                : located.keys().stream().map(Db2PlanAccess.StatementKey::identity).toList().toString();
        return PlanDiagnostic.of(
                PlanCodes.AUXILIARY_CLEANUP_FAILED,
                "DB2 Explain request could not be cleaned without risking another request: "
                        + requestIdentity + ", window=" + start + ".." + end + ", candidates=" + candidates
                        + (identityError == null ? "" : ", reason=" + identityError.message())
        );
    }

    private static PlanDiagnostic cleanupDiagnostic(
            SQLException error,
            String requestIdentity,
            Db2PlanAccess.StatementKey key
    ) {
        return new PlanDiagnostic(
                PlanCodes.AUXILIARY_CLEANUP_FAILED,
                "Failed to delete DB2 Explain rows for " + requestIdentity
                        + (key == null ? "" : ", statementKey=" + key.identity())
                        + ": " + message(error),
                error.getSQLState(),
                Integer.toString(error.getErrorCode())
        );
    }

    private static String materializedLocator(Db2PlanAccess.LocateResult located, int maxRawBytes) {
        com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
        com.google.gson.JsonArray sets = new com.google.gson.JsonArray();
        sets.add(located.statement().json());
        envelope.add("resultSets", sets);
        envelope.addProperty("truncated", located.statement().truncated());
        String json = org.jumpserver.chen.framework.datasource.plan.ExecutionPlanJson.GSON.toJson(envelope);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxRawBytes) {
            return json;
        }
        com.google.gson.JsonObject minimal = new com.google.gson.JsonObject();
        minimal.add("resultSets", new com.google.gson.JsonArray());
        minimal.addProperty("truncated", true);
        return org.jumpserver.chen.framework.datasource.plan.ExecutionPlanJson.GSON.toJson(minimal);
    }

    private static PlanEffects.TransactionState transactionEffect(TransactionCheck transaction, boolean wroteRows) {
        return transaction.participates() && wroteRows
                ? PlanEffects.TransactionState.PARTICIPATED
                : PlanEffects.TransactionState.UNCHANGED;
    }

    private static PlanEffects effects(
            PlanEffects.AuxiliaryStorage auxiliary,
            PlanEffects.TransactionState transaction,
            PlanEffects.ConnectionDisposition disposition
    ) {
        return new PlanEffects(PlanEffects.SessionState.UNCHANGED, transaction, auxiliary, disposition);
    }

    private static int positiveQueryNo() {
        return RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
    }

    private static String queryTag() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "CHEN" + HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    }

    private static String classifyTableError(SQLException error) {
        if (error == null) return PlanCodes.SQL_ERROR;
        String state = error.getSQLState();
        int code = error.getErrorCode();
        if ("42704".equals(state) || code == -204 || code == -219) return PlanCodes.PLAN_TABLE_MISSING;
        if ("42703".equals(state) || code == -206) return PlanCodes.PLAN_TABLE_INCOMPATIBLE;
        if ((state != null && state.startsWith("425")) || code == -551 || code == -552) {
            return PlanCodes.PLAN_PERMISSION_DENIED;
        }
        return PlanCodes.SQL_ERROR;
    }

    private static String remediation(String code) {
        return switch (code) {
            case PlanCodes.PLAN_TABLE_MISSING ->
                    "Ask a DBA to install the complete DB2 LUW Explain table set outside Chen";
            case PlanCodes.PLAN_TABLE_INCOMPATIBLE ->
                    "Ask a DBA to upgrade the DB2 LUW Explain table set outside Chen";
            case PlanCodes.PLAN_PERMISSION_DENIED ->
                    "Grant catalog access and SELECT, INSERT, and DELETE on the Explain table set";
            default -> "Resolve the DB2 prerequisite error and retry";
        };
    }

    private static boolean isConnectionBroken(SQLException error) {
        return error != null && error.getSQLState() != null && error.getSQLState().startsWith("08");
    }

    private static boolean isTransactionRollback(SQLException error) {
        return error != null && error.getSQLState() != null && error.getSQLState().startsWith("40");
    }

    private static boolean isCancelled(PlanExecutionContext context, SQLException error) {
        String message = error == null ? null : error.getMessage();
        return context.isCancelled()
                || (error != null && "57014".equals(error.getSQLState()))
                || (message != null && message.toLowerCase(Locale.ROOT).contains("cancel"));
    }

    private static PlanDiagnostic sqlDiagnostic(String code, String message, SQLException error) {
        return new PlanDiagnostic(
                code,
                message,
                error == null ? null : error.getSQLState(),
                error == null ? null : Integer.toString(error.getErrorCode())
        );
    }

    private static SQLException accumulate(SQLException current, SQLException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private static String message(SQLException error) {
        return error == null || error.getMessage() == null
                ? "DB2 execution plan operation failed"
                : error.getMessage();
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

    private record ServerIdentity(
            String productName,
            String productVersion,
            String serviceLevel,
            String driverName,
            String driverVersion
    ) {
        String displayVersion() {
            return productName + " " + productVersion + " (" + serviceLevel + ")";
        }
    }
}
