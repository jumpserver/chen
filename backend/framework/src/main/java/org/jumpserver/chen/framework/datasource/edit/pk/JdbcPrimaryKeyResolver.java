package org.jumpserver.chen.framework.datasource.edit.pk;

import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.edit.analyzer.EditabilityReason;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcPrimaryKeyResolver implements PrimaryKeyResolver {
    private final Connection connection;
    private final DbType dbType;

    public JdbcPrimaryKeyResolver(Connection connection, DbType dbType) {
        this.connection = connection;
        this.dbType = dbType;
    }

    @Override
    public PrimaryKeyResolution resolvePrimaryKeys(String schema, String table) throws SQLException {
        return switch (this.dbType) {
            case postgresql -> this.resolvePostgresqlPrimaryKeysWithTableCheck(schema, table);
            case mysql, mariadb -> this.resolveMysqlPrimaryKeysWithTableCheck(schema, table);
            case oracle -> this.resolveOraclePrimaryKeysWithTableCheck(schema, table);
            case sqlserver -> this.resolveSqlServerPrimaryKeysWithTableCheck(schema, table);
            case dm -> this.resolveDamengPrimaryKeysWithTableCheck(schema, table);
            case db2 -> this.resolveDb2PrimaryKeysWithTableCheck(schema, table);
            default -> null;
        };
    }

    private PrimaryKeyResolution resolvePostgresqlPrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        if (this.isPostgresqlView(schema, table)) {
            return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
        }
        return PrimaryKeyResolution.primaryKeys(this.resolvePostgresqlPrimaryKeys(schema, table));
    }

    private PrimaryKeyResolution resolveMysqlPrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        if (this.isMysqlView(schema, table)) {
            return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
        }
        return PrimaryKeyResolution.primaryKeys(this.resolveMysqlPrimaryKeys(schema, table));
    }

    private PrimaryKeyResolution resolveOraclePrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        if (this.isOracleView(schema, table)) {
            return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
        }
        return PrimaryKeyResolution.primaryKeys(this.resolveOraclePrimaryKeys(schema, table));
    }

    private PrimaryKeyResolution resolveSqlServerPrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        if (this.isSqlServerView(schema, table)) {
            return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
        }
        return PrimaryKeyResolution.primaryKeys(this.resolveSqlServerPrimaryKeys(schema, table));
    }

    private PrimaryKeyResolution resolveDamengPrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        var metadata = this.connection.getMetaData();
        String schemaPattern = StringUtils.trimToNull(schema);
        try (var resultSet = metadata.getTables(null, schemaPattern, table, new String[]{"VIEW"})) {
            if (resultSet.next()) {
                return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
            }
        }

        List<String> primaryKeys = new ArrayList<>();
        try (var resultSet = metadata.getPrimaryKeys(null, schemaPattern, table)) {
            while (resultSet.next()) {
                primaryKeys.add(resultSet.getString("COLUMN_NAME"));
            }
        }
        return PrimaryKeyResolution.primaryKeys(primaryKeys);
    }

    private PrimaryKeyResolution resolveDb2PrimaryKeysWithTableCheck(String schema, String table) throws SQLException {
        if (this.isDb2View(schema, table)) {
            return PrimaryKeyResolution.readOnly(EditabilityReason.VIEW_NOT_SUPPORTED);
        }
        return PrimaryKeyResolution.primaryKeys(this.resolveDb2PrimaryKeys(schema, table));
    }

    private boolean isPostgresqlView(String schema, String table) throws SQLException {
        String sql = """
                SELECT table_type
                FROM information_schema.tables
                WHERE table_schema = COALESCE(?, current_schema())
                  AND table_name = ?
                """;
        return this.isView(sql, schema, table);
    }

    private boolean isMysqlView(String schema, String table) throws SQLException {
        String sql = """
                SELECT table_type
                FROM information_schema.tables
                WHERE table_schema = COALESCE(?, DATABASE())
                  AND table_name = ?
                """;
        return this.isView(sql, schema, table);
    }

    private boolean isOracleView(String schema, String table) throws SQLException {
        String sql = """
                SELECT 'VIEW'
                FROM all_views
                WHERE owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
                  AND view_name = ?
                """;
        return this.isView(sql, schema, table);
    }

    private boolean isSqlServerView(String schema, String table) throws SQLException {
        String sql = """
                SELECT 'VIEW'
                FROM sys.views v
                JOIN sys.schemas s
                  ON v.schema_id = s.schema_id
                WHERE s.name = COALESCE(?, 'dbo')
                  AND v.name = ?
                """;
        return this.isView(sql, schema, table);
    }

    private boolean isDb2View(String schema, String table) throws SQLException {
        String sql = """
                SELECT 'VIEW'
                FROM syscat.tables
                WHERE tabschema = COALESCE(?, CURRENT SCHEMA)
                  AND tabname = ?
                  AND type = 'V'
                """;
        return this.isView(sql, schema, table);
    }

    private boolean isView(String sql, String schema, String table) throws SQLException {
        try (var statement = this.connection.prepareStatement(sql)) {
            bindSchemaAndTable(statement, schema, table);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() && StringUtils.equalsIgnoreCase(resultSet.getString(1), "VIEW");
            }
        }
    }

    private List<String> resolvePostgresqlPrimaryKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                 AND tc.table_name = kcu.table_name
                WHERE tc.constraint_type = 'PRIMARY KEY'
                  AND tc.table_schema = COALESCE(?, current_schema())
                  AND tc.table_name = ?
                ORDER BY kcu.ordinal_position
                """;
        return this.queryPrimaryKeys(sql, schema, table);
    }

    private List<String> resolveMysqlPrimaryKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE constraint_name = 'PRIMARY'
                  AND table_schema = COALESCE(?, DATABASE())
                  AND table_name = ?
                ORDER BY ordinal_position
                """;
        return this.queryPrimaryKeys(sql, schema, table);
    }

    private List<String> resolveOraclePrimaryKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT acc.column_name
                FROM all_constraints ac
                JOIN all_cons_columns acc
                  ON ac.owner = acc.owner
                 AND ac.constraint_name = acc.constraint_name
                 AND ac.table_name = acc.table_name
                WHERE ac.constraint_type = 'P'
                  AND ac.owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
                  AND ac.table_name = ?
                ORDER BY acc.position
                """;
        return this.queryPrimaryKeys(sql, schema, table);
    }

    private List<String> resolveSqlServerPrimaryKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT c.name
                FROM sys.key_constraints kc
                JOIN sys.index_columns ic
                  ON kc.parent_object_id = ic.object_id
                 AND kc.unique_index_id = ic.index_id
                JOIN sys.columns c
                  ON ic.object_id = c.object_id
                 AND ic.column_id = c.column_id
                JOIN sys.tables t
                  ON kc.parent_object_id = t.object_id
                JOIN sys.schemas s
                  ON t.schema_id = s.schema_id
                WHERE kc.type = 'PK'
                  AND s.name = COALESCE(?, 'dbo')
                  AND t.name = ?
                ORDER BY ic.key_ordinal
                """;
        return this.queryPrimaryKeys(sql, schema, table);
    }

    private List<String> resolveDb2PrimaryKeys(String schema, String table) throws SQLException {
        String sql = """
                SELECT kcu.colname
                FROM syscat.tabconst tc
                JOIN syscat.keycoluse kcu
                  ON tc.constname = kcu.constname
                 AND tc.tabschema = kcu.tabschema
                 AND tc.tabname = kcu.tabname
                WHERE tc.type = 'P'
                  AND tc.tabschema = COALESCE(?, CURRENT SCHEMA)
                  AND tc.tabname = ?
                ORDER BY kcu.colseq
                """;
        return this.queryPrimaryKeys(sql, schema, table);
    }

    private List<String> queryPrimaryKeys(String sql, String schema, String table) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();
        try (var statement = this.connection.prepareStatement(sql)) {
            bindSchemaAndTable(statement, schema, table);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    primaryKeys.add(resultSet.getString(1));
                }
            }
        }
        return primaryKeys;
    }

    private static void bindSchemaAndTable(java.sql.PreparedStatement statement, String schema, String table) throws SQLException {
        if (StringUtils.isBlank(schema)) {
            statement.setNull(1, java.sql.Types.VARCHAR);
        } else {
            statement.setString(1, schema);
        }
        statement.setString(2, table);
    }
}
