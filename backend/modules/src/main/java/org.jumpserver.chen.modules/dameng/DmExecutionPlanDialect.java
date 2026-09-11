package org.jumpserver.chen.modules.dameng;

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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DmExecutionPlanDialect extends BaseExecutionPlanDialect {
    /* Real-database validated pairs are reported as such; unvalidated pairs remain testable candidates. */
    private static final Set<String> VALIDATED_SERVER_VERSIONS = Set.of();
    private static final Set<String> VALIDATED_DRIVER_VERSIONS = Set.of();
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
            "abs", "avg", "cast", "ceil", "ceiling", "coalesce", "concat", "count",
            "current_date", "current_time", "current_timestamp", "dateadd", "datediff",
            "dense_rank", "extract", "floor", "greatest", "lag", "lead", "least",
            "length", "lower", "ltrim", "max", "min", "mod", "nullif", "rank",
            "replace", "round", "row_number", "rtrim", "substr", "substring", "sum",
            "trim", "trunc", "upper"
    );

    private final ExecutionPlanCapabilities capabilities = ExecutionPlanCapabilities.dm();
    private final Set<String> validatedServerVersions;
    private final Set<String> validatedDriverVersions;

    public DmExecutionPlanDialect() {
        this(VALIDATED_SERVER_VERSIONS, VALIDATED_DRIVER_VERSIONS);
    }

    DmExecutionPlanDialect(Set<String> validatedServerVersions, Set<String> validatedDriverVersions) {
        this.validatedServerVersions = Set.copyOf(validatedServerVersions);
        this.validatedDriverVersions = Set.copyOf(validatedDriverVersions);
    }

    @Override
    public PlanDatabase database() {
        return PlanDatabase.dm;
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
                    "Use only validated DM built-in functions and ordinary tables"
            ));
            return unmet(context.serverVersion(), prerequisites, unsafe);
        }

        PlanTransactionState transactionState = context.transactionState();
        if (context.transactionProbeFailed() || transactionState != PlanTransactionState.AUTO_COMMIT) {
            String detail = context.transactionProbeDetail();
            String message = "DM EXPLAIN is enabled only for a verified auto-commit idle connection"
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message,
                    "Finish the transaction or reconnect in auto-commit mode"
            ));
            return unmet(context.serverVersion(), prerequisites,
                    PlanDiagnostic.of(PlanCodes.TRANSACTION_CONTEXT_UNSAFE, message));
        }

        VersionIdentity version = versionIdentity(context);
        boolean versionValidated = validated(version);
        if (!versionValidated) {
            String message = "DM server/driver pair has not passed the real JDBC release gate: server="
                    + version.serverVersion() + ", driver=" + version.driverVersion();
            prerequisites.add(PlanPrerequisite.unknown(
                    PlanCodes.VERSION_NOT_VALIDATED, message,
                    "Treat this run as validation evidence until the pair passes the release gate"
            ));
        } else {
            prerequisites.add(PlanPrerequisite.met("server-version", version.serverVersion()));
            prerequisites.add(PlanPrerequisite.met("jdbc-driver", version.driverName() + " " + version.driverVersion()));
        }

        prerequisites.add(PlanPrerequisite.met("statement", "Single SELECT using ordinary EXPLAIN"));
        BoundedRaw raw = executeExplain(context, statement.sql());
        if (raw.truncated()) {
            return rawOnly(version.serverVersion(), prerequisites, raw,
                    PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "DM EXPLAIN text exceeded the display limit"));
        }
        DmPlanParser.ParseResult parsed = DmPlanParser.parse(raw.text(), context.maxNodes(), context.maxDepth());
        if (!parsed.structured()) {
            return rawOnly(version.serverVersion(), prerequisites, raw, parsed.error());
        }
        return new DialectPlanResult(
                version.serverVersion(), PlanStatus.SUCCESS, parsed.roots(), raw.text(), PlanRawFormat.TEXT,
                DmPlanParser.RAW_FORMAT_VERSION, false, capabilities, prerequisites,
                PlanEffects.unchangedReuse(), null, parsed.warnings()
        );
    }

    private BoundedRaw executeExplain(PlanExecutionContext context, String sql) throws SQLException {
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            context.throwIfCancelled();
            statement = context.connection().createStatement();
            context.registerStatement(statement);
            resultSet = statement.executeQuery("EXPLAIN " + sql);
            ResultSetMetaData metadata = resultSet.getMetaData();
            StringBuilder raw = new StringBuilder();
            while (resultSet.next()) {
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    if (column > 1) raw.append('\t');
                    String value = resultSet.getString(column);
                    if (value != null) raw.append(value);
                }
                raw.append('\n');
                if (utf8ByteLength(raw) > context.maxRawBytes()) break;
            }
            return boundRaw(raw.toString(), context.maxRawBytes());
        } catch (SQLException e) {
            throwIfConnectionUnusable(context, e, capabilities);
            throw e;
        } finally {
            closeQuietly(resultSet);
            context.unregisterStatement(statement);
            closeQuietly(statement);
        }
    }

    private VersionIdentity versionIdentity(PlanExecutionContext context) throws SQLException {
        DatabaseMetaData metadata = context.connection().getMetaData();
        String server = metadata.getDatabaseProductVersion();
        return new VersionIdentity(server, metadata.getDriverName(), metadata.getDriverVersion());
    }

    private boolean validated(VersionIdentity version) {
        return validatedServerVersions.stream().anyMatch(version.serverVersion()::startsWith)
                && validatedDriverVersions.stream().anyMatch(version.driverVersion()::startsWith);
    }

    static PlanDiagnostic unsafeFunctions(SqlStatementAnalysis statement) {
        for (String rawName : statement.functionNames()) {
            String name = rawName == null ? "" : rawName.replace("\"", "").trim().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot >= 0 || !SAFE_FUNCTIONS.contains(name)) {
                return PlanDiagnostic.of(PlanCodes.UNSAFE_TO_ESTIMATE,
                        "Function " + rawName + " is not a validated DM built-in");
            }
        }
        return null;
    }

    private DialectPlanResult unmet(String serverVersion, List<PlanPrerequisite> prerequisites, PlanDiagnostic error) {
        return new DialectPlanResult(serverVersion, PlanStatus.PREREQUISITES_UNMET, List.of(), null, null,
                null, false, capabilities, prerequisites, PlanEffects.unchangedReuse(), error, List.of());
    }

    private DialectPlanResult rawOnly(
            String serverVersion, List<PlanPrerequisite> prerequisites, BoundedRaw raw, PlanDiagnostic warning
    ) {
        return new DialectPlanResult(serverVersion, PlanStatus.RAW_ONLY, List.of(), raw.text(), PlanRawFormat.TEXT,
                DmPlanParser.RAW_FORMAT_VERSION, raw.truncated(), capabilities, prerequisites,
                PlanEffects.unchangedReuse(), null, warning == null ? List.of() : List.of(warning));
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
