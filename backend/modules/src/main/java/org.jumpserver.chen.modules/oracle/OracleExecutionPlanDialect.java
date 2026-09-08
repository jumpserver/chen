package org.jumpserver.chen.modules.oracle;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.BaseExecutionPlanDialect;
import org.jumpserver.chen.framework.datasource.plan.ConnectionInvalidatedException;
import org.jumpserver.chen.framework.datasource.plan.DialectPlanResult;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanCapabilities;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanJson;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDatabase;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanEffects;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanLimits;
import org.jumpserver.chen.framework.datasource.plan.PlanPrerequisite;
import org.jumpserver.chen.framework.datasource.plan.PlanRawFormat;
import org.jumpserver.chen.framework.datasource.plan.PlanStatus;
import org.jumpserver.chen.framework.datasource.plan.PlanTransactionState;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class OracleExecutionPlanDialect extends BaseExecutionPlanDialect {
    static final String RAW_FORMAT_VERSION = "chen-table-v1";
    private static final String PLAN_TABLE = "PLAN_TABLE";
    private static final int MAX_SYNONYM_DEPTH = 8;

    private static final List<String> PLAN_COLUMNS = List.of(
            "STATEMENT_ID", "PLAN_ID", "TIMESTAMP", "REMARKS", "OPERATION", "OPTIONS",
            "OBJECT_NODE", "OBJECT_OWNER", "OBJECT_NAME", "OBJECT_ALIAS", "OBJECT_INSTANCE",
            "OBJECT_TYPE", "OPTIMIZER", "SEARCH_COLUMNS", "ID", "PARENT_ID", "DEPTH",
            "POSITION", "COST", "CARDINALITY", "BYTES", "OTHER_TAG", "PARTITION_START",
            "PARTITION_STOP", "PARTITION_ID", "OTHER", "DISTRIBUTION", "CPU_COST", "IO_COST",
            "TEMP_SPACE", "ACCESS_PREDICATES", "FILTER_PREDICATES", "PROJECTION", "TIME",
            "QBLOCK_NAME", "OTHER_XML"
    );
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "STATEMENT_ID", "OPERATION", "ID", "PARENT_ID", "POSITION", "COST", "CARDINALITY"
    );
    private static final Set<String> CHARACTER_COLUMNS = Set.of("STATEMENT_ID", "OPERATION");
    private static final Set<String> NUMBER_COLUMNS = Set.of("ID", "PARENT_ID", "POSITION", "COST", "CARDINALITY");
    private static final Set<String> REQUIRED_PRIVILEGES = Set.of("SELECT", "INSERT", "DELETE");

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

        if (!isValidatedVersion(context.serverVersion())) {
            String version = context.serverVersion();
            String message = version == null || version.isBlank()
                    ? "Oracle server version could not be determined"
                    : "Oracle server version has not been validated for estimated plans: " + version;
            PlanPrerequisite versionFailure = PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED,
                    message,
                    "Use the validated Oracle 19c baseline or complete the version safety checks"
            );
            prerequisites.add(versionFailure);
            return unmet(context, prerequisites, PlanDiagnostic.of(versionFailure.code(), message));
        }
        prerequisites.add(PlanPrerequisite.met("server-version", "Oracle 19c validated baseline"));

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

        PlanTableResolution resolution = resolvePlanTable(context);
        if (resolution.failure() != null) {
            prerequisites.add(resolution.failure());
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(resolution.failure().code(), resolution.failure().message())
            );
        }
        PlanTableTarget target = resolution.target();
        prerequisites.add(PlanPrerequisite.met(
                "plan-table",
                "Using " + target.qualifiedName() + (target.temporary() ? " (global temporary)" : "")
        ));

        PlanPrerequisite lifecycleFailure = validateLifecycle(target, transaction);
        if (lifecycleFailure != null) {
            prerequisites.add(lifecycleFailure);
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(lifecycleFailure.code(), lifecycleFailure.message())
            );
        }
        prerequisites.add(PlanPrerequisite.met("plan-row-lifecycle", planRowLifecycle(target)));

        PlanPrerequisite columnFailure = validateColumns(target.columns());
        if (columnFailure != null) {
            prerequisites.add(columnFailure);
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(columnFailure.code(), columnFailure.message())
            );
        }
        prerequisites.add(PlanPrerequisite.met("plan-table-columns", "PLAN_TABLE has compatible columns"));

        Set<String> privileges = loadPrivileges(context, target);
        Set<String> missingPrivileges = new LinkedHashSet<>(REQUIRED_PRIVILEGES);
        missingPrivileges.removeAll(privileges);
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
            PlanTableTarget target,
            TransactionCheck transaction,
            List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        String statementId = statementId();
        RawCapture raw = null;
        SQLException originalError = null;
        boolean generationAttempted = false;

        try {
            context.throwIfCancelled();
            generationAttempted = true;
            executeExplain(context, statement, target, statementId);
            raw = readPlanRows(context, target, statementId);
        } catch (SQLException e) {
            originalError = e;
        }

        SQLException cleanupError = null;
        if (generationAttempted) {
            try {
                deletePlanRows(context, target, statementId);
            } catch (SQLException e) {
                cleanupError = e;
            }
        }

        if (cleanupError != null) {
            boolean discard = transaction.participates()
                    || context.isCancelled()
                    || isConnectionBroken(cleanupError)
                    || (originalError != null && isConnectionBroken(originalError));
            if (discard) {
                throw invalidatedAfterCleanupFailure(
                        context,
                        originalError,
                        cleanupError,
                        raw,
                        prerequisites,
                        transaction,
                        statementId
                );
            }

            PlanDiagnostic cleanupWarning = cleanupDiagnostic(cleanupError, statementId);
            PlanEffects effects = effects(
                    transaction,
                    PlanEffects.AuxiliaryStorage.RESIDUAL,
                    PlanEffects.ConnectionDisposition.REUSE,
                    PlanEffects.TransactionState.UNCHANGED
            );
            if (originalError != null) {
                return errorResult(context, originalError, raw, prerequisites, effects, List.of(cleanupWarning));
            }
            return resultFromRaw(context, raw, prerequisites, effects, List.of(cleanupWarning));
        }

        if (originalError != null) {
            if (isConnectionBroken(originalError)) {
                throw invalidated(context, originalError, null, PlanEffects.discard(
                        PlanEffects.SessionState.UNKNOWN,
                        PlanEffects.TransactionState.UNKNOWN
                ));
            }
            PlanEffects.TransactionState transactionEffect = transaction.participates()
                    ? PlanEffects.TransactionState.PARTICIPATED
                    : PlanEffects.TransactionState.UNCHANGED;
            return errorResult(
                    context,
                    originalError,
                    raw,
                    prerequisites,
                    effects(
                            transaction,
                            PlanEffects.AuxiliaryStorage.CLEANED,
                            PlanEffects.ConnectionDisposition.REUSE,
                            transactionEffect
                    ),
                    List.of()
            );
        }

        PlanEffects.TransactionState transactionEffect = transaction.participates()
                ? PlanEffects.TransactionState.PARTICIPATED
                : PlanEffects.TransactionState.UNCHANGED;
        return resultFromRaw(
                context,
                raw,
                prerequisites,
                effects(
                        transaction,
                        PlanEffects.AuxiliaryStorage.CLEANED,
                        PlanEffects.ConnectionDisposition.REUSE,
                        transactionEffect
                ),
                List.of()
        );
    }

    private void executeExplain(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            PlanTableTarget target,
            String statementId
    ) throws SQLException {
        String sql = "EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' INTO "
                + target.qualifiedName() + " FOR " + statement.sql();
        try (TrackedStatement tracked = trackedStatement(context, true)) {
            tracked.statement().execute(sql);
        }
    }

    private RawCapture readPlanRows(
            PlanExecutionContext context,
            PlanTableTarget target,
            String statementId
    ) throws SQLException {
        List<String> columns = target.selectedColumns();
        String selected = columns.stream().map(OracleExecutionPlanDialect::quote).reduce((a, b) -> a + ", " + b).orElseThrow();
        String sql = "SELECT " + selected + " FROM " + target.qualifiedName()
                + " WHERE " + quote("STATEMENT_ID") + " = ? ORDER BY " + quote("ID") + ", " + quote("POSITION");

        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, true)) {
            tracked.statement().setString(1, statementId);
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                return captureTable(resultSet, context.maxRawBytes(), context.maxNodes());
            }
        }
    }

    private void deletePlanRows(
            PlanExecutionContext context,
            PlanTableTarget target,
            String statementId
    ) throws SQLException {
        String sql = "DELETE FROM " + target.qualifiedName() + " WHERE " + quote("STATEMENT_ID") + " = ?";
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, false)) {
            tracked.statement().setString(1, statementId);
            tracked.statement().executeUpdate();
        }
    }

    private RawCapture captureTable(ResultSet resultSet, int maxRawBytes, int maxRows) throws SQLException {
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
        int capturedBytes = ExecutionPlanJson.GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8).length;
        while (resultSet.next()) {
            if (rowCount >= maxRows) {
                truncated = true;
                break;
            }
            JsonArray row = new JsonArray();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                row.add(jsonValue(resultSet, metadata.getColumnType(index), index));
            }
            int rowBytes = ExecutionPlanJson.GSON.toJson(row).getBytes(StandardCharsets.UTF_8).length;
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
            json = ExecutionPlanJson.GSON.toJson(minimal);
            truncated = true;
        }
        return new RawCapture(json, truncated);
    }

    private static com.google.gson.JsonElement jsonValue(ResultSet resultSet, int jdbcType, int index)
            throws SQLException {
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

    private DialectPlanResult resultFromRaw(
            PlanExecutionContext context,
            RawCapture raw,
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
            RawCapture raw,
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

    private PlanTableResolution resolvePlanTable(PlanExecutionContext context) throws SQLException {
        SessionIdentity identity = sessionIdentity(context);
        String owner = identity.currentSchema();
        String name = PLAN_TABLE;
        Set<String> visited = new HashSet<>();

        ObjectType local = objectType(context, owner, name);
        if (local == null) {
            Synonym synonym = synonym(context, "PUBLIC", name);
            if (synonym == null) {
                return PlanTableResolution.failed(PlanPrerequisite.unmet(
                        PlanCodes.PLAN_TABLE_MISSING,
                        "PLAN_TABLE is not resolvable in the current Oracle schema",
                        "Create a compatible private PLAN_TABLE or grant access through the standard public synonym"
                ));
            }
            owner = synonym.owner();
            name = synonym.name();
            if (synonym.databaseLink() != null) {
                return remotePlanTable();
            }
        } else if (local == ObjectType.SYNONYM) {
            Synonym synonym = synonym(context, owner, name);
            if (synonym == null) {
                return PlanTableResolution.failed(PlanPrerequisite.unmet(
                        PlanCodes.PLAN_TABLE_MISSING,
                        "The private PLAN_TABLE synonym cannot be resolved",
                        "Repair or remove the private synonym"
                ));
            }
            owner = synonym.owner();
            name = synonym.name();
            if (synonym.databaseLink() != null) {
                return remotePlanTable();
            }
        } else if (local != ObjectType.TABLE) {
            return incompatibleObject(owner, name, local);
        }

        for (int depth = 0; depth < MAX_SYNONYM_DEPTH; depth++) {
            String key = owner + "." + name;
            if (!visited.add(key)) {
                return PlanTableResolution.failed(PlanPrerequisite.unmet(
                        PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                        "PLAN_TABLE synonym resolution contains a cycle",
                        "Repair the PLAN_TABLE synonym chain"
                ));
            }

            TableInfo table = tableInfo(context, owner, name);
            if (table != null) {
                Map<String, String> columns = tableColumns(context, owner, name);
                return PlanTableResolution.resolved(new PlanTableTarget(
                        owner,
                        name,
                        table.temporary(),
                        table.duration(),
                        columns,
                        identity.sessionUser()
                ));
            }

            ObjectType type = objectType(context, owner, name);
            if (type != ObjectType.SYNONYM) {
                if (type != null) {
                    return incompatibleObject(owner, name, type);
                }
                return PlanTableResolution.failed(PlanPrerequisite.unmet(
                        PlanCodes.PLAN_TABLE_MISSING,
                        "Resolved PLAN_TABLE target " + quote(owner) + "." + quote(name) + " is not accessible",
                        "Grant access to a compatible PLAN_TABLE target"
                ));
            }
            Synonym nested = synonym(context, owner, name);
            if (nested == null) {
                return PlanTableResolution.failed(PlanPrerequisite.unmet(
                        PlanCodes.PLAN_TABLE_MISSING,
                        "PLAN_TABLE synonym target cannot be resolved",
                        "Repair the PLAN_TABLE synonym chain"
                ));
            }
            if (nested.databaseLink() != null) {
                return remotePlanTable();
            }
            owner = nested.owner();
            name = nested.name();
        }
        return PlanTableResolution.failed(PlanPrerequisite.unmet(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "PLAN_TABLE synonym chain is too deep",
                "Point PLAN_TABLE directly at a compatible local table"
        ));
    }

    private SessionIdentity sessionIdentity(PlanExecutionContext context) throws SQLException {
        String sql = "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA'), USER FROM DUAL";
        try (TrackedStatement tracked = trackedStatement(context, true);
             ResultSet resultSet = tracked.statement().executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("Oracle session identity query returned no row");
            }
            return new SessionIdentity(resultSet.getString(1), resultSet.getString(2));
        }
    }

    private ObjectType objectType(PlanExecutionContext context, String owner, String name) throws SQLException {
        String sql = "SELECT OBJECT_TYPE FROM ALL_OBJECTS "
                + "WHERE OWNER = ? AND OBJECT_NAME = ? "
                + "AND OBJECT_TYPE IN ('TABLE','VIEW','SYNONYM') "
                + "ORDER BY CASE OBJECT_TYPE WHEN 'TABLE' THEN 1 WHEN 'VIEW' THEN 2 ELSE 3 END";
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return ObjectType.valueOf(resultSet.getString(1).toUpperCase(Locale.ROOT));
            }
        }
    }

    private Synonym synonym(PlanExecutionContext context, String owner, String name) throws SQLException {
        String sql = "SELECT TABLE_OWNER, TABLE_NAME, DB_LINK FROM ALL_SYNONYMS "
                + "WHERE OWNER = ? AND SYNONYM_NAME = ?";
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Synonym(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3)
                );
            }
        }
    }

    private TableInfo tableInfo(PlanExecutionContext context, String owner, String name) throws SQLException {
        String sql = "SELECT TEMPORARY, DURATION FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new TableInfo("Y".equalsIgnoreCase(resultSet.getString(1)), resultSet.getString(2));
            }
        }
    }

    private Map<String, String> tableColumns(
            PlanExecutionContext context,
            String owner,
            String name
    ) throws SQLException {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS "
                + "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
        Map<String, String> columns = new LinkedHashMap<>();
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.put(upper(resultSet.getString(1)), upper(resultSet.getString(2)));
                }
            }
        }
        return columns;
    }

    private Set<String> loadPrivileges(PlanExecutionContext context, PlanTableTarget target) throws SQLException {
        if (target.owner().equals(target.sessionUser())) {
            return REQUIRED_PRIVILEGES;
        }

        Set<String> privileges = new HashSet<>();
        String grantsSql = "SELECT DISTINCT PRIVILEGE FROM ALL_TAB_PRIVS "
                + "WHERE OWNER = ? AND TABLE_NAME = ? "
                + "AND (GRANTEE = ? OR GRANTEE = 'PUBLIC' OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))";
        try (TrackedPreparedStatement tracked = trackedPreparedStatement(context, grantsSql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, target.owner());
            statement.setString(2, target.name());
            statement.setString(3, target.sessionUser());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    privileges.add(upper(resultSet.getString(1)));
                }
            }
        }

        String systemSql = "SELECT PRIVILEGE FROM SESSION_PRIVS "
                + "WHERE PRIVILEGE IN ('SELECT ANY TABLE','INSERT ANY TABLE','DELETE ANY TABLE')";
        try (TrackedStatement tracked = trackedStatement(context, true);
             ResultSet resultSet = tracked.statement().executeQuery(systemSql)) {
            while (resultSet.next()) {
                String privilege = upper(resultSet.getString(1));
                if (privilege != null && privilege.endsWith(" ANY TABLE")) {
                    privileges.add(privilege.substring(0, privilege.length() - " ANY TABLE".length()));
                }
            }
        }
        return privileges;
    }

    private PlanPrerequisite validateLifecycle(PlanTableTarget target, TransactionCheck transaction) {
        if (!target.temporary()) {
            return null;
        }
        String duration = upper(target.duration());
        if (!"SYS$SESSION".equals(duration) && !"SYS$TRANSACTION".equals(duration)) {
            return PlanPrerequisite.unmet(
                    PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                    "PLAN_TABLE has an unknown temporary-row duration",
                    "Use a standard Oracle PLAN_TABLE definition"
            );
        }
        if (transaction.autoCommit() && "SYS$TRANSACTION".equals(duration)) {
            return PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "PLAN_TABLE deletes rows on commit, so auto-commit would erase the plan before it can be read",
                    "Use the standard ON COMMIT PRESERVE ROWS PLAN_TABLE or an active transaction"
            );
        }
        return null;
    }

    private PlanPrerequisite validateColumns(Map<String, String> columns) {
        Set<String> missing = new LinkedHashSet<>(REQUIRED_COLUMNS);
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            return PlanPrerequisite.unmet(
                    PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                    "PLAN_TABLE is missing required columns: " + String.join(", ", missing),
                    "Replace it with a compatible Oracle PLAN_TABLE definition"
            );
        }
        for (String name : CHARACTER_COLUMNS) {
            if (!isCharacterType(columns.get(name))) {
                return incompatibleColumn(name, columns.get(name));
            }
        }
        for (String name : NUMBER_COLUMNS) {
            if (!isNumberType(columns.get(name))) {
                return incompatibleColumn(name, columns.get(name));
            }
        }
        return null;
    }

    private static PlanPrerequisite incompatibleColumn(String name, String type) {
        return PlanPrerequisite.unmet(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "PLAN_TABLE column " + name + " has incompatible type " + type,
                "Replace it with a compatible Oracle PLAN_TABLE definition"
        );
    }

    private static boolean isCharacterType(String type) {
        return type != null && (type.contains("CHAR") || "CLOB".equals(type));
    }

    private static boolean isNumberType(String type) {
        return type != null && (type.contains("NUMBER") || type.contains("FLOAT") || type.contains("DOUBLE")
                || type.contains("INTEGER") || type.contains("DECIMAL"));
    }

    private TrackedStatement trackedStatement(PlanExecutionContext context, boolean checkCancellation)
            throws SQLException {
        if (checkCancellation) {
            context.throwIfCancelled();
        }
        Statement statement = context.connection().createStatement();
        context.registerStatement(statement);
        try {
            configureTimeout(statement, context, checkCancellation);
            return new TrackedStatement(statement, context);
        } catch (SQLException e) {
            context.unregisterStatement(statement);
            closeQuietly(statement);
            throw e;
        }
    }

    private TrackedPreparedStatement trackedPreparedStatement(
            PlanExecutionContext context,
            String sql,
            boolean checkCancellation
    ) throws SQLException {
        if (checkCancellation) {
            context.throwIfCancelled();
        }
        PreparedStatement statement = context.connection().prepareStatement(sql);
        context.registerStatement(statement);
        try {
            configureTimeout(statement, context, checkCancellation);
            return new TrackedPreparedStatement(statement, context);
        } catch (SQLException e) {
            context.unregisterStatement(statement);
            closeQuietly(statement);
            throw e;
        }
    }

    private static void configureTimeout(
            Statement statement,
            PlanExecutionContext context,
            boolean useRequestDeadline
    ) throws SQLException {
        long remainingMillis = useRequestDeadline
                ? Math.max(1L, context.deadlineMillis() - System.currentTimeMillis())
                : PlanLimits.CLEANUP_TIMEOUT_MS;
        long seconds = Math.max(1L, (remainingMillis + 999L) / 1_000L);
        statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
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

    private static String planRowLifecycle(PlanTableTarget target) {
        if (!target.temporary()) {
            return "Regular table rows are isolated by generated STATEMENT_ID and explicitly deleted";
        }
        return "Temporary table duration " + target.duration() + " is compatible with this transaction state";
    }

    private static PlanTableResolution remotePlanTable() {
        return PlanTableResolution.failed(PlanPrerequisite.unmet(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "A remote PLAN_TABLE synonym is not supported",
                "Point PLAN_TABLE at a compatible table in the current database"
        ));
    }

    private static PlanTableResolution incompatibleObject(String owner, String name, ObjectType type) {
        return PlanTableResolution.failed(PlanPrerequisite.unmet(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "Resolved PLAN_TABLE object " + quote(owner) + "." + quote(name) + " is a " + type,
                "Use a compatible Oracle table for PLAN_TABLE"
        ));
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static boolean isConnectionBroken(SQLException error) {
        String state = error.getSQLState();
        return state != null && state.startsWith("08");
    }

    private static boolean isValidatedVersion(String version) {
        return version != null && version.matches("(?is).*\\b19(?:c|\\.).*");
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
            TransactionCheck transaction,
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
            RawCapture raw,
            List<PlanPrerequisite> prerequisites,
            TransactionCheck transaction,
            String statementId
    ) {
        SQLException cause = original == null ? cleanup : original;
        PlanDiagnostic originalDiagnostic = original == null
                ? cleanupDiagnostic(cleanup, statementId)
                : sqlDiagnostic(isCancelled(context, original) ? PlanCodes.CANCELLED : PlanCodes.SQL_ERROR, original);
        PlanDiagnostic cleanupDiagnostic = cleanupDiagnostic(cleanup, statementId);
        List<PlanDiagnostic> warnings = List.of(cleanupDiagnostic);
        PlanEffects partialEffects = effects(
                transaction,
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

    private enum ObjectType {
        TABLE,
        VIEW,
        SYNONYM
    }

    private record SessionIdentity(String currentSchema, String sessionUser) {
    }

    private record Synonym(String owner, String name, String databaseLink) {
    }

    private record TableInfo(boolean temporary, String duration) {
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

    private record PlanTableTarget(
            String owner,
            String name,
            boolean temporary,
            String duration,
            Map<String, String> columns,
            String sessionUser
    ) {
        String qualifiedName() {
            return quote(owner) + "." + quote(name);
        }

        List<String> selectedColumns() {
            List<String> selected = new ArrayList<>();
            for (String column : PLAN_COLUMNS) {
                if (columns.containsKey(column)) {
                    selected.add(column);
                }
            }
            return selected;
        }
    }

    private record PlanTableResolution(PlanTableTarget target, PlanPrerequisite failure) {
        static PlanTableResolution resolved(PlanTableTarget target) {
            return new PlanTableResolution(target, null);
        }

        static PlanTableResolution failed(PlanPrerequisite failure) {
            return new PlanTableResolution(null, failure);
        }
    }

    private record RawCapture(String json, boolean truncated) {
    }

    private static final class TrackedStatement implements AutoCloseable {
        private final Statement statement;
        private final PlanExecutionContext context;

        private TrackedStatement(Statement statement, PlanExecutionContext context) {
            this.statement = statement;
            this.context = context;
        }

        private Statement statement() {
            return statement;
        }

        @Override
        public void close() throws SQLException {
            context.unregisterStatement(statement);
            statement.close();
        }
    }

    private static final class TrackedPreparedStatement implements AutoCloseable {
        private final PreparedStatement statement;
        private final PlanExecutionContext context;

        private TrackedPreparedStatement(PreparedStatement statement, PlanExecutionContext context) {
            this.statement = statement;
            this.context = context;
        }

        private PreparedStatement statement() {
            return statement;
        }

        @Override
        public void close() throws SQLException {
            context.unregisterStatement(statement);
            statement.close();
        }
    }
}
