package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanPrerequisite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class OraclePlanTable {
    private static final String NAME = "PLAN_TABLE";
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

    private OraclePlanTable() {
    }

    static Resolution resolve(PlanExecutionContext context) throws SQLException {
        SessionIdentity identity = sessionIdentity(context);
        String owner = identity.currentSchema();
        String name = NAME;
        Set<String> visited = new HashSet<>();

        for (int depth = 0; depth <= MAX_SYNONYM_DEPTH; depth++) {
            String key = owner + "." + name;
            if (!visited.add(key)) {
                return failed(
                        PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                        "PLAN_TABLE synonym resolution contains a cycle",
                        "Repair the PLAN_TABLE synonym chain"
                );
            }

            ObjectInfo object = objectInfo(context, owner, name);
            if (object != null && object.type() == ObjectType.TABLE) {
                if (object.temporary() == null) {
                    return failed(
                            PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                            "PLAN_TABLE table metadata is not accessible",
                            "Grant access to a compatible PLAN_TABLE target"
                    );
                }
                return Resolution.resolved(new Target(
                        owner,
                        name,
                        object.temporary(),
                        object.duration(),
                        tableColumns(context, owner, name),
                        identity.sessionUser()
                ));
            }

            ObjectType type = object == null ? null : object.type();
            if (type != null && type != ObjectType.SYNONYM) {
                return incompatibleObject(owner, name, type);
            }

            Synonym synonym;
            if (type == ObjectType.SYNONYM) {
                synonym = synonym(context, owner, name);
            } else if (depth == 0) {
                synonym = synonym(context, "PUBLIC", name);
            } else {
                synonym = null;
            }
            if (synonym == null) {
                String message = depth == 0
                        ? "PLAN_TABLE is not resolvable in the current Oracle schema"
                        : "PLAN_TABLE synonym target cannot be resolved";
                String remediation = depth == 0
                        ? "Create a compatible private PLAN_TABLE or grant access through the standard public synonym"
                        : "Repair the PLAN_TABLE synonym chain";
                return failed(PlanCodes.PLAN_TABLE_MISSING, message, remediation);
            }
            if (synonym.databaseLink() != null) {
                return failed(
                        PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                        "A remote PLAN_TABLE synonym is not supported",
                        "Point PLAN_TABLE at a compatible table in the current database"
                );
            }
            owner = synonym.owner();
            name = synonym.name();
        }
        return failed(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "PLAN_TABLE synonym chain is too deep",
                "Point PLAN_TABLE directly at a compatible local table"
        );
    }

    static PlanPrerequisite validateLifecycle(Target target, boolean autoCommit) {
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
        if (autoCommit && "SYS$TRANSACTION".equals(duration)) {
            return PlanPrerequisite.unmet(
                    PlanCodes.TRANSACTION_CONTEXT_UNSAFE,
                    "PLAN_TABLE deletes rows on commit, so auto-commit would erase the plan before it can be read",
                    "Use the standard ON COMMIT PRESERVE ROWS PLAN_TABLE or an active transaction"
            );
        }
        return null;
    }

    static PlanPrerequisite validateColumns(Target target) {
        Map<String, String> columns = target.columns();
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

    static Set<String> missingPrivileges(PlanExecutionContext context, Target target) throws SQLException {
        Set<String> missing = new LinkedHashSet<>(REQUIRED_PRIVILEGES);
        missing.removeAll(loadPrivileges(context, target));
        return missing;
    }

    static String lifecycleDescription(Target target) {
        if (!target.temporary()) {
            return "Regular table rows are isolated by generated STATEMENT_ID and explicitly deleted";
        }
        return "Temporary table duration " + target.duration() + " is compatible with this transaction state";
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static SessionIdentity sessionIdentity(PlanExecutionContext context) throws SQLException {
        String sql = "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA'), USER FROM DUAL";
        try (OraclePlanJdbc.Tracked<Statement> tracked = OraclePlanJdbc.statement(context, true);
             ResultSet resultSet = tracked.statement().executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("Oracle session identity query returned no row");
            }
            return new SessionIdentity(resultSet.getString(1), resultSet.getString(2));
        }
    }

    private static ObjectInfo objectInfo(PlanExecutionContext context, String owner, String name)
            throws SQLException {
        String sql = "SELECT o.OBJECT_TYPE, t.TEMPORARY, t.DURATION FROM ALL_OBJECTS o "
                + "LEFT JOIN ALL_TABLES t ON t.OWNER = o.OWNER AND t.TABLE_NAME = o.OBJECT_NAME "
                + "WHERE o.OWNER = ? AND o.OBJECT_NAME = ? "
                + "AND o.OBJECT_TYPE IN ('TABLE','VIEW','SYNONYM') "
                + "ORDER BY CASE o.OBJECT_TYPE WHEN 'TABLE' THEN 1 WHEN 'VIEW' THEN 2 ELSE 3 END";
        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked = OraclePlanJdbc.preparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ObjectType type = ObjectType.valueOf(resultSet.getString(1).toUpperCase(Locale.ROOT));
                String temporary = resultSet.getString(2);
                return new ObjectInfo(
                        type,
                        temporary == null ? null : "Y".equalsIgnoreCase(temporary),
                        resultSet.getString(3)
                );
            }
        }
    }

    private static Synonym synonym(PlanExecutionContext context, String owner, String name) throws SQLException {
        String sql = "SELECT TABLE_OWNER, TABLE_NAME, DB_LINK FROM ALL_SYNONYMS "
                + "WHERE OWNER = ? AND SYNONYM_NAME = ?";
        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked = OraclePlanJdbc.preparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, owner);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new Synonym(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3))
                        : null;
            }
        }
    }

    private static Map<String, String> tableColumns(
            PlanExecutionContext context,
            String owner,
            String name
    ) throws SQLException {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS "
                + "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
        Map<String, String> columns = new LinkedHashMap<>();
        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked = OraclePlanJdbc.preparedStatement(context, sql, true)) {
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

    private static Set<String> loadPrivileges(PlanExecutionContext context, Target target) throws SQLException {
        if (target.owner().equals(target.sessionUser())) {
            return REQUIRED_PRIVILEGES;
        }

        Set<String> privileges = new HashSet<>();
        String grantsSql = "SELECT DISTINCT PRIVILEGE FROM ALL_TAB_PRIVS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "AND (GRANTEE = ? OR GRANTEE = 'PUBLIC' OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))";
        try (OraclePlanJdbc.Tracked<PreparedStatement> tracked =
                     OraclePlanJdbc.preparedStatement(context, grantsSql, true)) {
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
        try (OraclePlanJdbc.Tracked<Statement> tracked = OraclePlanJdbc.statement(context, true);
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

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static Resolution incompatibleObject(String owner, String name, ObjectType type) {
        return failed(
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                "Resolved PLAN_TABLE object " + quote(owner) + "." + quote(name) + " is a " + type,
                "Use a compatible Oracle table for PLAN_TABLE"
        );
    }

    private static Resolution failed(String code, String message, String remediation) {
        return Resolution.failed(PlanPrerequisite.unmet(code, message, remediation));
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

    private record ObjectInfo(ObjectType type, Boolean temporary, String duration) {
    }

    record Target(
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

    record Resolution(Target target, PlanPrerequisite failure) {
        static Resolution resolved(Target target) {
            return new Resolution(target, null);
        }

        static Resolution failed(PlanPrerequisite failure) {
            return new Resolution(null, failure);
        }
    }
}
