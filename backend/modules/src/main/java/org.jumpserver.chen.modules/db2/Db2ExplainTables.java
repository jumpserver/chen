package org.jumpserver.chen.modules.db2;

import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanExecutionContext;
import org.jumpserver.chen.framework.datasource.plan.PlanPrerequisite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Db2ExplainTables {
    static final List<String> TABLES = List.of(
            "EXPLAIN_INSTANCE",
            "EXPLAIN_STATEMENT",
            "EXPLAIN_OPERATOR",
            "EXPLAIN_STREAM",
            "EXPLAIN_OBJECT",
            "EXPLAIN_PREDICATE",
            "EXPLAIN_ARGUMENT",
            "EXPLAIN_DIAGNOSTIC",
            "EXPLAIN_DIAGNOSTIC_DATA"
    );
    static final List<String> STATEMENT_KEY = List.of(
            "EXPLAIN_REQUESTER", "EXPLAIN_TIME", "SOURCE_NAME", "SOURCE_SCHEMA",
            "SOURCE_VERSION", "EXPLAIN_LEVEL", "STMTNO", "SECTNO"
    );
    static final List<String> INSTANCE_KEY = STATEMENT_KEY.subList(0, 5);

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = requiredColumns();
    private static final Set<String> CHARACTER_KEY_COLUMNS = Set.of(
            "EXPLAIN_REQUESTER", "SOURCE_NAME", "SOURCE_SCHEMA", "SOURCE_VERSION", "EXPLAIN_LEVEL",
            "QUERYTAG", "OPERATOR_TYPE", "SOURCE_TYPE", "TARGET_TYPE", "OBJECT_SCHEMA", "OBJECT_NAME",
            "OBJECT_TYPE", "PREDICATE_TEXT", "ARGUMENT_TYPE", "ARGUMENT_VALUE", "TOKEN"
    );
    private static final Set<String> NUMERIC_KEY_COLUMNS = Set.of(
            "STMTNO", "SECTNO", "QUERYNO", "OPERATOR_ID", "STREAM_ID", "SOURCE_ID", "TARGET_ID",
            "TOTAL_COST", "STREAM_COUNT", "PREDICATE_ID", "DIAGNOSTIC_ID", "CODE", "ORDINAL"
    );
    private static final Set<String> TIMESTAMP_COLUMNS = Set.of(
            "EXPLAIN_TIME", "CREATE_TIME"
    );

    private Db2ExplainTables() {
    }

    static Resolution resolve(PlanExecutionContext context) throws SQLException {
        Identity identity = identity(context);
        SchemaInventory auth = inventory(context, identity.authorizationId());
        if (auth.presentCount() == TABLES.size()) {
            return validate(context, identity, auth);
        }
        if (auth.presentCount() != 0) {
            return failed(
                    identity,
                    PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                    "Authorization schema " + quote(identity.authorizationId())
                            + " contains only part of the required DB2 Explain table set: "
                            + String.join(", ", auth.presentTables()),
                    "Install or upgrade the complete DB2 LUW Explain table set outside Chen"
            );
        }

        SchemaInventory systools = inventory(context, "SYSTOOLS");
        if (systools.presentCount() == TABLES.size()) {
            return validate(context, identity, systools);
        }
        if (systools.presentCount() != 0) {
            return failed(
                    identity,
                    PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                    "SYSTOOLS contains only part of the required DB2 Explain table set: "
                            + String.join(", ", systools.presentTables()),
                    "Install or upgrade the complete SYSTOOLS Explain table set outside Chen"
            );
        }
        return failed(
                identity,
                PlanCodes.PLAN_TABLE_MISSING,
                "No complete DB2 LUW Explain table set exists in authorization schema "
                        + quote(identity.authorizationId()) + " or SYSTOOLS",
                "Ask a DBA to install compatible Explain tables; Chen never creates them"
        );
    }

    private static Resolution validate(
            PlanExecutionContext context,
            Identity identity,
            SchemaInventory inventory
    ) throws SQLException {
        for (String table : TABLES) {
            String objectType = inventory.objectTypes().get(table);
            if (!"T".equals(objectType) && !"A".equals(objectType)) {
                return failed(
                        identity,
                        PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                        inventory.qualified(table) + " is not a table or alias",
                        "Replace it with a compatible DB2 LUW Explain table or alias"
                );
            }
        }

        Map<String, Map<String, String>> columns = columns(context, inventory.schema());
        for (Map.Entry<String, Set<String>> requirement : REQUIRED_COLUMNS.entrySet()) {
            Map<String, String> actual = columns.getOrDefault(requirement.getKey(), Map.of());
            Set<String> missing = new LinkedHashSet<>(requirement.getValue());
            missing.removeAll(actual.keySet());
            if (!missing.isEmpty()) {
                return failed(
                        identity,
                        PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                        inventory.qualified(requirement.getKey()) + " is missing required columns: "
                                + String.join(", ", missing),
                        "Upgrade the DB2 LUW Explain table set outside Chen"
                );
            }
            for (String name : requirement.getValue()) {
                String type = actual.get(name);
                if (CHARACTER_KEY_COLUMNS.contains(name) && !isCharacter(type)) {
                    return incompatibleColumn(identity, inventory, requirement.getKey(), name, type);
                }
                if (TIMESTAMP_COLUMNS.contains(name) && !isTimestamp(type)) {
                    return incompatibleColumn(identity, inventory, requirement.getKey(), name, type);
                }
                if (NUMERIC_KEY_COLUMNS.contains(name) && !isNumeric(type)) {
                    return incompatibleColumn(identity, inventory, requirement.getKey(), name, type);
                }
            }
        }

        Set<String> missingPrivileges = missingPrivileges(context, identity, inventory);
        if (!missingPrivileges.isEmpty()) {
            return failed(
                    identity,
                    PlanCodes.PLAN_PERMISSION_DENIED,
                    "Missing DB2 Explain table privileges: " + String.join(", ", missingPrivileges),
                    "Grant SELECT, INSERT, and DELETE on the resolved Explain table set"
            );
        }
        return new Resolution(
                new Target(inventory.schema(), identity),
                null
        );
    }

    private static Identity identity(PlanExecutionContext context) throws SQLException {
        String sql = "VALUES (SESSION_USER, CURRENT SCHEMA)";
        try (Db2PlanJdbc.Tracked<Statement> tracked = Db2PlanJdbc.statement(context, true);
             ResultSet resultSet = tracked.statement().executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("DB2 session identity query returned no row");
            }
            return new Identity(upper(resultSet.getString(1)), upper(resultSet.getString(2)));
        }
    }

    private static SchemaInventory inventory(PlanExecutionContext context, String schema) throws SQLException {
        String placeholders = String.join(",", TABLES.stream().map(ignored -> "?").toList());
        String sql = "SELECT TABNAME, TYPE FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TABNAME IN ("
                + placeholders + ")";
        Map<String, String> types = new LinkedHashMap<>();
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, schema);
            for (int i = 0; i < TABLES.size(); i++) {
                statement.setString(i + 2, TABLES.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    types.put(upper(resultSet.getString(1)), upper(resultSet.getString(2)));
                }
            }
        }
        return new SchemaInventory(schema, types);
    }

    private static Map<String, Map<String, String>> columns(
            PlanExecutionContext context,
            String schema
    ) throws SQLException {
        String placeholders = String.join(",", TABLES.stream().map(ignored -> "?").toList());
        String sql = "SELECT TABNAME, COLNAME, TYPENAME FROM SYSCAT.COLUMNS "
                + "WHERE TABSCHEMA = ? AND TABNAME IN (" + placeholders + ") ORDER BY TABNAME, COLNO";
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, sql, true)) {
            PreparedStatement statement = tracked.statement();
            statement.setString(1, schema);
            for (int i = 0; i < TABLES.size(); i++) {
                statement.setString(i + 2, TABLES.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.computeIfAbsent(upper(resultSet.getString(1)), ignored -> new LinkedHashMap<>())
                            .put(upper(resultSet.getString(2)), upper(resultSet.getString(3)));
                }
            }
        }
        return result;
    }

    private static Set<String> missingPrivileges(
            PlanExecutionContext context,
            Identity identity,
            SchemaInventory inventory
    ) throws SQLException {
        Set<String> authIds = new HashSet<>();
        authIds.add(identity.authorizationId());
        authIds.add("PUBLIC");
        String groupsSql = "SELECT \"GROUP\" FROM TABLE "
                + "(SYSPROC.AUTH_LIST_GROUPS_FOR_AUTHID(?)) AS G";
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, groupsSql, true)) {
            tracked.statement().setString(1, identity.authorizationId());
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                while (resultSet.next()) {
                    authIds.add(upper(resultSet.getString(1)));
                }
            }
        } catch (SQLException ignored) {
            // Direct ownership and grants are still checked. Runtime SQL errors remain classified.
        }

        if (hasDataAccess(context, authIds)) {
            return Set.of();
        }

        Map<String, Privileges> byTable = new HashMap<>();
        String placeholders = String.join(",", authIds.stream().map(ignored -> "?").toList());
        String tablePlaceholders = String.join(",", TABLES.stream().map(ignored -> "?").toList());
        String sql = "SELECT t.TABNAME, t.OWNER, a.CONTROLAUTH, a.SELECTAUTH, a.INSERTAUTH, a.DELETEAUTH "
                + "FROM SYSCAT.TABLES t LEFT JOIN SYSCAT.TABAUTH a "
                + "ON a.TABSCHEMA=t.TABSCHEMA AND a.TABNAME=t.TABNAME AND a.GRANTEE IN ("
                + placeholders + ") WHERE t.TABSCHEMA=? AND t.TABNAME IN (" + tablePlaceholders + ")";
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, sql, true)) {
            int index = 1;
            for (String authId : authIds) {
                tracked.statement().setString(index++, authId);
            }
            tracked.statement().setString(index++, inventory.schema());
            for (String table : TABLES) {
                tracked.statement().setString(index++, table);
            }
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                while (resultSet.next()) {
                    String table = upper(resultSet.getString(1));
                    String owner = upper(resultSet.getString(2));
                    Privileges privileges = byTable.computeIfAbsent(table, ignored -> new Privileges());
                    if (identity.authorizationId().equals(owner)) {
                        privileges.all = true;
                    }
                    privileges.merge(
                            resultSet.getString(3), resultSet.getString(4),
                            resultSet.getString(5), resultSet.getString(6)
                    );
                }
            }
        }

        Set<String> missing = new LinkedHashSet<>();
        for (String table : TABLES) {
            Privileges privilege = byTable.getOrDefault(table, new Privileges());
            if (!privilege.select) missing.add(table + ":SELECT");
            if (!privilege.insert) missing.add(table + ":INSERT");
            if (!privilege.delete) missing.add(table + ":DELETE");
        }
        return missing;
    }

    private static boolean hasDataAccess(PlanExecutionContext context, Set<String> authIds) throws SQLException {
        String placeholders = String.join(",", authIds.stream().map(ignored -> "?").toList());
        String sql = "SELECT 1 FROM SYSCAT.DBAUTH WHERE GRANTEE IN (" + placeholders
                + ") AND DATAACCESSAUTH='Y' FETCH FIRST 1 ROW ONLY";
        try (Db2PlanJdbc.Tracked<PreparedStatement> tracked =
                     Db2PlanJdbc.preparedStatement(context, sql, true)) {
            int index = 1;
            for (String authId : authIds) {
                tracked.statement().setString(index++, authId);
            }
            try (ResultSet resultSet = tracked.statement().executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    static String qualified(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    private static Resolution incompatibleColumn(
            Identity identity,
            SchemaInventory inventory,
            String table,
            String column,
            String type
    ) {
        return failed(
                identity,
                PlanCodes.PLAN_TABLE_INCOMPATIBLE,
                inventory.qualified(table) + " column " + column + " has incompatible type " + type,
                "Upgrade the DB2 LUW Explain table set outside Chen"
        );
    }

    private static boolean isCharacter(String type) {
        return type != null && (type.contains("CHAR") || type.contains("CLOB"));
    }

    private static boolean isTimestamp(String type) {
        return type != null && type.contains("TIMESTAMP");
    }

    private static boolean isNumeric(String type) {
        return type != null && (type.contains("INT") || type.contains("DECIMAL")
                || type.contains("NUMERIC") || type.contains("DOUBLE") || type.contains("REAL"));
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Resolution failed(Identity identity, String code, String message, String remediation) {
        return new Resolution(null, PlanPrerequisite.unmet(code, message, remediation));
    }

    private static Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put("EXPLAIN_INSTANCE", Set.copyOf(INSTANCE_KEY));
        result.put("EXPLAIN_STATEMENT", with(STATEMENT_KEY, "QUERYNO", "QUERYTAG"));
        result.put("EXPLAIN_OPERATOR", with(STATEMENT_KEY, "OPERATOR_ID", "OPERATOR_TYPE", "TOTAL_COST"));
        result.put("EXPLAIN_STREAM", with(
                STATEMENT_KEY, "STREAM_ID", "SOURCE_TYPE", "SOURCE_ID", "TARGET_TYPE", "TARGET_ID",
                "OBJECT_SCHEMA", "OBJECT_NAME", "STREAM_COUNT"
        ));
        result.put("EXPLAIN_OBJECT", with(
                STATEMENT_KEY, "OBJECT_SCHEMA", "OBJECT_NAME", "OBJECT_TYPE", "CREATE_TIME"
        ));
        result.put("EXPLAIN_PREDICATE", with(
                STATEMENT_KEY, "OPERATOR_ID", "PREDICATE_ID", "PREDICATE_TEXT"
        ));
        result.put("EXPLAIN_ARGUMENT", with(
                STATEMENT_KEY, "OPERATOR_ID", "ARGUMENT_TYPE", "ARGUMENT_VALUE"
        ));
        result.put("EXPLAIN_DIAGNOSTIC", with(STATEMENT_KEY, "DIAGNOSTIC_ID", "CODE"));
        result.put("EXPLAIN_DIAGNOSTIC_DATA", with(
                STATEMENT_KEY, "DIAGNOSTIC_ID", "ORDINAL", "TOKEN"
        ));
        return Map.copyOf(result);
    }

    private static Set<String> with(List<String> base, String... extra) {
        Set<String> result = new LinkedHashSet<>(base);
        result.addAll(List.of(extra));
        return Set.copyOf(result);
    }

    record Identity(String authorizationId, String currentSchema) {
    }

    record Target(String schema, Identity identity) {
        String qualified(String table) {
            return Db2ExplainTables.qualified(schema, table);
        }
    }

    record Resolution(Target target, PlanPrerequisite failure) {
    }

    private record SchemaInventory(String schema, Map<String, String> objectTypes) {
        int presentCount() {
            return objectTypes.size();
        }

        List<String> presentTables() {
            return new ArrayList<>(objectTypes.keySet());
        }

        String qualified(String table) {
            return Db2ExplainTables.qualified(schema, table);
        }
    }

    private static final class Privileges {
        private boolean all;
        private boolean select;
        private boolean insert;
        private boolean delete;

        private void merge(String control, String select, String insert, String delete) {
            all |= granted(control);
            this.select |= granted(select);
            this.insert |= granted(insert);
            this.delete |= granted(delete);
            if (all) {
                this.select = true;
                this.insert = true;
                this.delete = true;
            }
        }

        private static boolean granted(String value) {
            return "Y".equalsIgnoreCase(value) || "G".equalsIgnoreCase(value);
        }
    }
}
