package org.jumpserver.chen.modules.clickhouse;

import org.jumpserver.chen.framework.datasource.analysis.SqlStatementAnalysis;
import org.jumpserver.chen.framework.datasource.plan.BaseExecutionPlanDialect;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ClickHouseExecutionPlanDialect extends BaseExecutionPlanDialect {
    /* Unvalidated pairs still use PLAN JSON as a testable candidate; text EXPLAIN is never generated. */
    private static final Set<String> VALIDATED_JSON_SERVER_VERSIONS = Set.of();
    private static final Set<String> VALIDATED_DRIVER_VERSIONS = Set.of();
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
            "abs", "arrayjoin", "avg", "cast", "coalesce", "concat", "count", "dateadd",
            "datediff", "dense_rank", "formatdatetime", "greatest", "if", "ifnull", "lag",
            "lead", "least", "length", "lower", "max", "min", "multiif", "now", "nullif",
            "rank", "row_number", "substring", "sum", "toDate", "todate", "todatetime",
            "tofloat64", "toint32", "toint64", "tostring", "touint32", "touint64", "trim",
            "upper"
    );
    private static final Set<String> EXTERNAL_FUNCTIONS = Set.of(
            "azureblobstorage", "cluster", "clusterallreplicas", "deltalake", "dictionary",
            "file", "hdfs", "hudi", "iceberg", "input", "jdbc", "mongodb", "mysql",
            "odbc", "postgresql", "remote", "remotesecure", "s3", "s3cluster", "url"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.clickhouse();
    private final Set<String> validatedJsonServerVersions;
    private final Set<String> validatedDriverVersions;

    public ClickHouseExecutionPlanDialect() {
        this(VALIDATED_JSON_SERVER_VERSIONS, VALIDATED_DRIVER_VERSIONS);
    }

    ClickHouseExecutionPlanDialect(Set<String> validatedJsonServerVersions, Set<String> validatedDriverVersions) {
        this.validatedJsonServerVersions = Set.copyOf(validatedJsonServerVersions);
        this.validatedDriverVersions = Set.copyOf(validatedDriverVersions);
    }

    @Override
    public PlanDatabase database() {
        return PlanDatabase.clickhouse;
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
                    PlanCodes.UNSAFE_TO_ESTIMATE, unsafe.message(),
                    "Use ordinary tables and validated ClickHouse built-in functions"
            ));
            return unmet(context.serverVersion(), prerequisites, unsafe);
        }
        if (!autoCommitIdle(context)) {
            String message = "ClickHouse experimental or unknown transaction context is not used for EXPLAIN";
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message,
                    "Finish the transaction or reconnect with auto-commit enabled"
            ));
            return unmet(context.serverVersion(), prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message));
        }

        VersionIdentity version = versionIdentity(context);
        boolean jsonValidated = validated(version, validatedJsonServerVersions);
        if (!jsonValidated) {
            String message = "ClickHouse server/driver pair has not passed the real JDBC PLAN release gate: server="
                    + version.serverVersion() + ", driver=" + version.driverVersion();
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED, message,
                    "Treat this PLAN JSON run as validation evidence until the pair passes the release gate"
            ));
        } else {
            prerequisites.add(PlanPrerequisite.met("server-version", version.serverVersion()));
            prerequisites.add(PlanPrerequisite.met("jdbc-driver", version.driverName() + " " + version.driverVersion()));
        }

        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT using EXPLAIN PLAN json=1"));
        return explainJson(context, statement, version.serverVersion(), prerequisites);
    }

    private DialectPlanResult explainJson(
            PlanExecutionContext context, SqlStatementAnalysis statement,
            String serverVersion, List<PlanPrerequisite> prerequisites
    ) throws SQLException {
        BoundedRaw raw = executeExplain(context, "EXPLAIN PLAN json=1 " + statement.sql());
        if (raw.truncated()) {
            return rawOnly(serverVersion, prerequisites, raw, PlanRawFormat.JSON,
                    ClickHousePlanParser.JSON_FORMAT_VERSION,
                    PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "ClickHouse PLAN JSON exceeded the display limit"));
        }
        ClickHousePlanParser.ParseResult parsed = ClickHousePlanParser.parseJson(
                raw.text(), context.maxNodes(), context.maxDepth()
        );
        if (!parsed.structured()) {
            return rawOnly(serverVersion, prerequisites, raw, PlanRawFormat.JSON,
                    ClickHousePlanParser.JSON_FORMAT_VERSION, parsed.error());
        }
        return new DialectPlanResult(serverVersion, PlanStatus.SUCCESS, parsed.roots(), raw.text(), PlanRawFormat.JSON,
                ClickHousePlanParser.JSON_FORMAT_VERSION, false, capabilities, prerequisites,
                PlanEffects.unchangedReuse(), null, parsed.warnings());
    }

    private BoundedRaw executeExplain(PlanExecutionContext context, String sql) throws SQLException {
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            context.throwIfCancelled();
            statement = context.connection().createStatement();
            context.registerStatement(statement);
            resultSet = statement.executeQuery(sql);
            StringBuilder raw = new StringBuilder();
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                if (value != null) raw.append(value);
                raw.append('\n');
                if (utf8ByteLength(raw) > context.maxRawBytes()) break;
            }
            return boundRaw(raw.toString().stripTrailing(), context.maxRawBytes());
        } catch (SQLException e) {
            throwIfConnectionUnusable(context, e, capabilities);
            throw e;
        } finally {
            closeQuietly(resultSet);
            context.unregisterStatement(statement);
            closeQuietly(statement);
        }
    }

    private static boolean autoCommitIdle(PlanExecutionContext context) throws SQLException {
        PlanTransactionState state = context.transactionState();
        if (context.transactionProbeFailed()) return false;
        if (state == PlanTransactionState.AUTO_COMMIT) return true;
        return state == PlanTransactionState.UNKNOWN && context.connection().getAutoCommit();
    }

    private VersionIdentity versionIdentity(PlanExecutionContext context) throws SQLException {
        DatabaseMetaData metadata = context.connection().getMetaData();
        return new VersionIdentity(
                metadata.getDatabaseProductVersion(), metadata.getDriverName(), metadata.getDriverVersion()
        );
    }

    private boolean validated(VersionIdentity version, Set<String> serverVersions) {
        return serverVersions.stream().anyMatch(version.serverVersion()::startsWith)
                && validatedDriverVersions.stream().anyMatch(version.driverVersion()::startsWith);
    }

    static PlanDiagnostic unsafeFunctions(SqlStatementAnalysis statement) {
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.replace("`", "").trim().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String function = dot < 0 ? name : name.substring(dot + 1);
            if (dot >= 0 || EXTERNAL_FUNCTIONS.contains(function) || !SAFE_FUNCTIONS.contains(function)) {
                return PlanDiagnostic.of(PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + rawName + " may perform external access or unknown schema inference");
            }
        }
        return null;
    }

    private DialectPlanResult unmet(String serverVersion, List<PlanPrerequisite> prerequisites, PlanDiagnostic error) {
        return new DialectPlanResult(serverVersion, PlanStatus.PREREQUISITES_UNMET, List.of(), null, null,
                null, false, capabilities, prerequisites, PlanEffects.unchangedReuse(), error, List.of());
    }

    private DialectPlanResult rawOnly(
            String serverVersion, List<PlanPrerequisite> prerequisites, BoundedRaw raw,
            PlanRawFormat format, String formatVersion, PlanDiagnostic warning
    ) {
        return new DialectPlanResult(serverVersion, PlanStatus.RAW_ONLY, List.of(), raw.text(), format,
                formatVersion, raw.truncated(), capabilities, prerequisites, PlanEffects.unchangedReuse(),
                null, warning == null ? List.of() : List.of(warning));
    }

    private record VersionIdentity(String serverVersion, String driverName, String driverVersion) {
        private VersionIdentity {
            serverVersion = valueOrUnknown(serverVersion);
            driverName = valueOrUnknown(driverName);
            driverVersion = valueOrUnknown(driverVersion);
        }

        private static String valueOrUnknown(String value) {
            return value == null || value.isBlank() ? "unknown" : value;
        }
    }
}
