package org.jumpserver.chen.modules.mysql;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.BaseExecutionPlanDialect;
import org.jumpserver.chen.framework.datasource.plan.ConnectionInvalidatedException;
import org.jumpserver.chen.framework.datasource.plan.DialectPlanResult;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanCapabilities;
import org.jumpserver.chen.framework.datasource.plan.NormalizedNodeType;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDatabase;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanEffects;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanNode;
import org.jumpserver.chen.framework.datasource.plan.PlanPrerequisite;
import org.jumpserver.chen.framework.datasource.plan.PlanRawFormat;
import org.jumpserver.chen.framework.datasource.plan.PlanStatus;
import org.jumpserver.chen.framework.datasource.plan.PlanTransactionState;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MysqlExecutionPlanDialect extends BaseExecutionPlanDialect {
    private static final Gson TABLE_GSON = new GsonBuilder().serializeNulls().create();
    private static final Set<String> SAFE_SCHEMAS = Set.of("mysql", "sys", "information_schema", "performance_schema");
    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "ifnull", "if", "nullif", "coalesce", "greatest", "least",
            "now", "curdate", "curtime", "current_date", "current_time", "current_timestamp", "unix_timestamp",
            "from_unixtime", "date_format", "str_to_date", "datediff", "timestampdiff", "date_add", "date_sub",
            "concat", "concat_ws", "length", "char_length", "substring", "substr", "left", "right", "trim",
            "ltrim", "rtrim", "replace", "lower", "upper", "cast", "convert", "abs", "ceil", "ceiling", "floor",
            "round", "mod", "md5", "sha1", "sha2", "json_extract", "json_unquote", "json_object", "json_array",
            "row_number", "rank", "dense_rank", "lag", "lead", "exists", "group_concat", "year", "month", "day",
            "hour", "minute", "second", "values", "interval"
    );
    private static final Set<String> UNSAFE_FUNCTIONS = Set.of(
            "load_file", "sleep", "benchmark", "sys_exec", "sys_eval", "sys_get", "sys_set",
            "updatexml", "extractvalue"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.mysql();

    @Override
    public PlanDatabase database() {
        return PlanDatabase.mysql;
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
            return unmet(context, prerequisites, unsafe);
        }
        if (context.transactionState() == PlanTransactionState.TRANSACTION_FAILED) {
            prerequisites.add(PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "The current MySQL transaction is already aborted",
                    "Rollback or finish the existing transaction, then retry"
            ));
            return unmet(
                    context,
                    prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, "Cannot estimate a plan in a failed transaction")
            );
        }

        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT"));
        PlanEffects effects = PlanEffects.unchangedReuse();
        if (jsonSupported(context.serverVersion())) {
            try {
                return explainJson(context, statement, prerequisites, effects);
            } catch (SQLException e) {
                if (!isJsonUnsupported(e)) {
                    throwIfInvalid(context, e);
                    throw e;
                }
            }
        }
        return explainClassic(context, statement, prerequisites, effects);
    }

    private DialectPlanResult explainJson(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects
    ) throws SQLException {
        BoundedRaw raw = executeExplain(context, "EXPLAIN FORMAT=JSON " + statement.sql());
        if (raw.truncated()) {
            return rawOnly(context, prerequisites, effects, raw.text(), PlanRawFormat.JSON, MysqlPlanParser.V1, true,
                    PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Raw plan exceeded the display limit"));
        }
        MysqlPlanParser.ParseResult parsed = MysqlPlanParser.parse(raw.text(), context.maxNodes(), context.maxDepth());
        if (!parsed.structured()) {
            return rawOnly(context, prerequisites, effects, raw.text(), PlanRawFormat.JSON, parsed.rawFormatVersion(), false,
                    parsed.warnings().isEmpty()
                            ? PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "MySQL plan JSON could not be normalized")
                            : parsed.warnings().get(parsed.warnings().size() - 1));
        }
        return new DialectPlanResult(
                context.serverVersion(),
                PlanStatus.SUCCESS,
                parsed.roots(),
                raw.text(),
                PlanRawFormat.JSON,
                parsed.rawFormatVersion(),
                false,
                capabilities,
                prerequisites,
                effects,
                null,
                parsed.warnings()
        );
    }

    private DialectPlanResult explainClassic(
            PlanExecutionContext context,
            SqlStatementAnalysis statement,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects
    ) throws SQLException {
        Connection connection = context.connection();
        Statement jdbcStatement = null;
        ResultSet resultSet = null;
        try {
            context.throwIfCancelled();
            jdbcStatement = connection.createStatement();
            context.registerStatement(jdbcStatement);
            resultSet = jdbcStatement.executeQuery("EXPLAIN " + statement.sql());
            TableEnvelope envelope = TableEnvelope.from(resultSet);
            String rawText = TABLE_GSON.toJson(envelope);
            BoundedRaw bounded = boundRaw(rawText, context.maxRawBytes());
            List<PlanNode> roots = envelope.toNodes();
            if (bounded.truncated() || roots.isEmpty()) {
                return rawOnly(
                        context,
                        prerequisites,
                        effects,
                        bounded.text(),
                        PlanRawFormat.TABLE,
                        "chen-table-v1",
                        bounded.truncated(),
                        PlanDiagnostic.of(
                                bounded.truncated() ? PlanCodes.PLAN_LIMIT_REACHED : PlanCodes.PLAN_PARSE_FAILED,
                                bounded.truncated() ? "Raw plan exceeded the display limit" : "Classic EXPLAIN produced no rows"
                        )
                );
            }
            return new DialectPlanResult(
                    context.serverVersion(),
                    PlanStatus.SUCCESS,
                    roots,
                    bounded.text(),
                    PlanRawFormat.TABLE,
                    "chen-table-v1",
                    false,
                    capabilities,
                    prerequisites,
                    effects,
                    null,
                    List.of()
            );
        } catch (SQLException e) {
            throwIfInvalid(context, e);
            throw e;
        } finally {
            closeQuietly(resultSet);
            context.unregisterStatement(jdbcStatement);
            closeQuietly(jdbcStatement);
        }
    }

    private BoundedRaw executeExplain(PlanExecutionContext context, String sql) throws SQLException {
        Connection connection = context.connection();
        Statement jdbcStatement = null;
        ResultSet resultSet = null;
        try {
            context.throwIfCancelled();
            jdbcStatement = connection.createStatement();
            context.registerStatement(jdbcStatement);
            resultSet = jdbcStatement.executeQuery(sql);
            String value = resultSet.next() ? resultSet.getString(1) : "";
            return boundRaw(value, context.maxRawBytes());
        } finally {
            closeQuietly(resultSet);
            context.unregisterStatement(jdbcStatement);
            closeQuietly(jdbcStatement);
        }
    }

    static boolean jsonSupported(String serverVersion) {
        if (serverVersion == null || serverVersion.isBlank()) {
            return true;
        }
        String numeric = serverVersion.replaceAll("[^0-9.].*$", "");
        String[] parts = numeric.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major > 5) {
                return true;
            }
            if (major < 5) {
                return false;
            }
            if (minor > 6) {
                return true;
            }
            if (minor < 6) {
                return false;
            }
            return patch >= 5;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    static boolean isJsonUnsupported(SQLException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("format") && (message.contains("json") || message.contains("explain"));
    }

    static PlanDiagnostic unsafeFunctions(SqlStatementAnalysis statement) {
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT).replace("\"", "").replace("`", "");
            int dot = lower.lastIndexOf('.');
            String schema = dot < 0 ? "" : lower.substring(0, dot);
            String function = dot < 0 ? lower : lower.substring(dot + 1);
            if (UNSAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(PlanCodes.UNSAFE_TO_ESTIMATE, "Function " + name + " is not safe to estimate");
            }
            if (!schema.isEmpty() && !SAFE_SCHEMAS.contains(schema)) {
                return PlanDiagnostic.of(PlanCodes.UNSAFE_TO_ESTIMATE, "Function " + name + " is outside known MySQL catalogs");
            }
            if (schema.isEmpty() && !BUILTIN_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(PlanCodes.UNSAFE_TO_ESTIMATE, "Function " + name + " is not a known MySQL builtin");
            }
        }
        return null;
    }

    private DialectPlanResult rawOnly(
            PlanExecutionContext context,
            List<PlanPrerequisite> prerequisites,
            PlanEffects effects,
            String rawText,
            PlanRawFormat format,
            String version,
            boolean truncated,
            PlanDiagnostic warning
    ) {
        return new DialectPlanResult(
                context.serverVersion(),
                PlanStatus.RAW_ONLY,
                List.of(),
                rawText,
                format,
                version,
                truncated,
                capabilities,
                prerequisites,
                effects,
                null,
                warning == null ? List.of() : List.of(warning)
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

    private void throwIfInvalid(PlanExecutionContext context, SQLException error) throws SQLException {
        String state = error.getSQLState();
        if (state != null && state.startsWith("08")) {
            throw new ConnectionInvalidatedException(
                    error.getMessage(),
                    error,
                    new PlanDiagnostic(PlanCodes.CONNECTION_INVALIDATED, error.getMessage(), error.getSQLState(), Integer.toString(error.getErrorCode())),
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
                            new PlanDiagnostic(PlanCodes.CONNECTION_INVALIDATED, error.getMessage(), error.getSQLState(), Integer.toString(error.getErrorCode())),
                            List.of()
                    )
            );
        }
    }

    static final class TableEnvelope {
        final List<ResultSetEnvelope> resultSets;

        TableEnvelope(List<ResultSetEnvelope> resultSets) {
            this.resultSets = resultSets;
        }

        static TableEnvelope from(ResultSet resultSet) throws SQLException {
            ResultSetMetaData meta = resultSet.getMetaData();
            List<Column> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(new Column(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
            }
            List<List<String>> rows = new ArrayList<>();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(resultSet.getString(i));
                }
                rows.add(row);
            }
            return new TableEnvelope(List.of(new ResultSetEnvelope("EXPLAIN", columns, rows)));
        }

        List<PlanNode> toNodes() {
            if (resultSets.isEmpty()) {
                return List.of();
            }
            ResultSetEnvelope set = resultSets.get(0);
            int tableIdx = set.indexOf("table");
            int typeIdx = set.indexOf("type");
            int rowsIdx = set.indexOf("rows");
            int extraIdx = set.indexOf("Extra");
            int keyIdx = set.indexOf("key");
            List<PlanNode> children = new ArrayList<>();
            int id = 1;
            for (List<String> row : set.rows) {
                String access = typeIdx >= 0 ? row.get(typeIdx) : "row";
                String table = tableIdx >= 0 ? row.get(tableIdx) : null;
                Map<String, String> attributes = new LinkedHashMap<>();
                if (keyIdx >= 0 && row.get(keyIdx) != null) {
                    attributes.put("key", row.get(keyIdx));
                }
                Map<String, String> predicates = new LinkedHashMap<>();
                if (extraIdx >= 0 && row.get(extraIdx) != null) {
                    predicates.put("Extra", row.get(extraIdx));
                }
                BigDecimal rows = null;
                try {
                    if (rowsIdx >= 0 && row.get(rowsIdx) != null) {
                        rows = new BigDecimal(row.get(rowsIdx));
                    }
                } catch (NumberFormatException ignored) {
                }
                children.add(new PlanNode(
                        "n" + id++,
                        null,
                        MysqlPlanParser.mapAccess(access, access),
                        access == null ? "row" : access,
                        null,
                        access,
                        extraIdx >= 0 ? row.get(extraIdx) : null,
                        table,
                        table,
                        rows,
                        null,
                        null,
                        rows != null ? MysqlPlanParser.ROWS_ESTIMATED : null,
                        null,
                        predicates,
                        attributes,
                        List.of()
                ));
            }
            if (children.isEmpty()) {
                return List.of();
            }
            return List.of(new PlanNode(
                    "n0",
                    null,
                    NormalizedNodeType.OTHER,
                    "EXPLAIN",
                    null,
                    "EXPLAIN",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    children
            ));
        }
    }

    private record ResultSetEnvelope(String name, List<Column> columns, List<List<String>> rows) {
        int indexOf(String label) {
            for (int i = 0; i < columns.size(); i++) {
                if (label.equalsIgnoreCase(columns.get(i).name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private record Column(String name, String type) {
    }
}
