package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ForeignKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.ObjectProperties;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.ObjectStatistics;
import org.jumpserver.chen.framework.datasource.metadata.PrimaryKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.SchemaMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ScopeKind;
import org.jumpserver.chen.framework.datasource.metadata.ScopeProperties;
import org.jumpserver.chen.framework.datasource.metadata.ScopeRef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MysqlMetadataProvider extends BaseDatabaseMetadataProvider {

    public MysqlMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, true, true, true, true,
            true, true, true, true, true, true, false
    );

    private static final String SQL_SCHEMAS = "SELECT SCHEMA_NAME AS name FROM INFORMATION_SCHEMA.SCHEMATA";

    private static final String SQL_TABLES = """
            SELECT t.TABLE_NAME AS name,
                   t.ENGINE AS engine,
                   c.CHARACTER_SET_NAME AS character_set,
                   t.TABLE_COLLATION AS collation,
                   t.TABLE_COMMENT AS comment
            FROM INFORMATION_SCHEMA.TABLES t
            LEFT JOIN INFORMATION_SCHEMA.COLLATION_CHARACTER_SET_APPLICABILITY c
              ON c.COLLATION_NAME = t.TABLE_COLLATION
            WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE <> 'VIEW'
            ORDER BY t.TABLE_NAME
            """;

    private static final String SQL_TABLE_STATS = """
            SELECT TABLE_NAME AS name,
                   TABLE_ROWS AS estimated_rows,
                   COALESCE(DATA_LENGTH, 0) + COALESCE(INDEX_LENGTH, 0) AS total_size_bytes
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_TYPE <> 'VIEW'
            """;

    private static final String SQL_VIEWS = """
            SELECT TABLE_NAME AS name, 'VIEW' AS type, NULL AS comment
            FROM INFORMATION_SCHEMA.VIEWS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME
            """;

    private static final String SQL_INDEXES = """
            SELECT INDEX_NAME AS name,
                   TABLE_NAME AS table_name,
                   COLUMN_NAME AS column_name,
                   CASE WHEN NON_UNIQUE = 0 THEN 1 ELSE 0 END AS is_unique,
                   INDEX_TYPE AS method,
                   NULL AS definition
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLUMN_NAME AS name, TABLE_NAME AS table_name, ORDINAL_POSITION AS ordinal,
                   COLUMN_TYPE AS native_type, DATA_TYPE AS jdbc_type_name,
                   COALESCE(CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, DATETIME_PRECISION) AS size,
                   NUMERIC_SCALE AS scale, IS_NULLABLE AS nullable,
                   COLUMN_DEFAULT AS default_value, COLUMN_COMMENT AS comment
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (__IN__)
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """;

    @Override
    public MetadataCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<SchemaMetadata> listSchemas(String catalog) throws SQLException {
        var result = new ArrayList<SchemaMetadata>();
        for (var row : query(SQL_SCHEMAS, List.of())) {
            result.add(new SchemaMetadata(null, stringValue(row, "name")));
        }
        return result;
    }

    @Override
    public List<RelationMetadata> listRelations(RelationScope scope, Set<RelationKind> kinds) throws SQLException {
        var result = new ArrayList<RelationMetadata>();
        if (kinds.contains(RelationKind.TABLE)) {
            for (var row : query(SQL_TABLES, List.of(scope.schema()))) {
                result.add(new RelationMetadata(
                        new ObjectRef(scope.catalog(), scope.schema(), stringValue(row, "name"), RelationKind.TABLE),
                        stringValue(row, "comment"),
                        stringValue(row, "engine"),
                        stringValue(row, "character_set"),
                        stringValue(row, "collation")
                ));
            }
        }
        if (kinds.contains(RelationKind.VIEW)) {
            for (var row : query(SQL_VIEWS, List.of(scope.schema()))) {
                result.add(new RelationMetadata(
                        new ObjectRef(scope.catalog(), scope.schema(), stringValue(row, "name"), RelationKind.VIEW),
                        stringValue(row, "comment"),
                        null,
                        null,
                        null
                ));
            }
        }
        return result;
    }

    @Override
    public List<ColumnMetadata> listColumns(List<ObjectRef> relations) throws SQLException {
        return loadColumns(SQL_COLUMNS, relations);
    }

    @Override
    public List<IndexMetadata> listIndexes(RelationScope scope) throws SQLException {
        return groupIndexRows(query(SQL_INDEXES, List.of(scope.schema())), scope);
    }

    @Override
    public List<ObjectStatistics> listStatistics(RelationScope scope) throws SQLException {
        var result = new ArrayList<ObjectStatistics>();
        for (var row : query(SQL_TABLE_STATS, List.of(scope.schema()))) {
            result.add(new ObjectStatistics(
                    new ObjectRef(scope.catalog(), scope.schema(), stringValue(row, "name"), RelationKind.TABLE),
                    longValue(row, "estimated_rows"),
                    longValue(row, "total_size_bytes")
            ));
        }
        return result;
    }

    @Override
    public String getSchemaDefinition(RelationScope scope) throws SQLException {
        var rows = query("SHOW CREATE DATABASE " + quoteIdentifier(scope.schema()), List.of());
        if (rows.isEmpty()) {
            return null;
        }
        var values = rows.get(0).values().iterator();
        values.next();
        return values.hasNext() ? String.valueOf(values.next()) : null;
    }

    private static final String SQL_TABLE_PROPERTIES = """
            SELECT TABLE_NAME, TABLE_SCHEMA, TABLE_TYPE, ENGINE, AVG_ROW_LENGTH, DATA_LENGTH,
                   MAX_DATA_LENGTH, CREATE_TIME, TABLE_COLLATION
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """;

    private static final String SQL_SCHEMA_PROPERTIES = """
            SELECT CATALOG_NAME, SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME, SQL_PATH
            FROM INFORMATION_SCHEMA.SCHEMATA
            WHERE SCHEMA_NAME = ?
            """;

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return loadObjectProperties(ref, SQL_TABLE_PROPERTIES);
    }

    @Override
    public ScopeProperties scopeProperties(ScopeRef ref) throws SQLException {
        if (ref.kind() != ScopeKind.SCHEMA) {
            return super.scopeProperties(ref);
        }
        return loadScopeProperties(ref, SQL_SCHEMA_PROPERTIES, List.of(ref.scope().schema()));
    }

    private static final String SQL_PRIMARY_KEYS = """
            SELECT k.TABLE_NAME AS table_name, k.COLUMN_NAME AS column_name, k.CONSTRAINT_NAME AS name
            FROM information_schema.KEY_COLUMN_USAGE k
            JOIN information_schema.TABLE_CONSTRAINTS t
              ON k.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = t.CONSTRAINT_NAME AND k.TABLE_NAME = t.TABLE_NAME
            WHERE t.CONSTRAINT_TYPE = 'PRIMARY KEY' AND t.TABLE_SCHEMA = ? AND k.TABLE_NAME IN (__IN__)
            ORDER BY k.TABLE_NAME, k.ORDINAL_POSITION
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT k.TABLE_NAME AS table_name, k.COLUMN_NAME AS column_name, k.CONSTRAINT_NAME AS name,
                   k.REFERENCED_TABLE_SCHEMA AS referenced_schema, k.REFERENCED_TABLE_NAME AS referenced_table,
                   k.REFERENCED_COLUMN_NAME AS referenced_column
            FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = ? AND k.REFERENCED_TABLE_NAME IS NOT NULL AND k.TABLE_NAME IN (__IN__)
            ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION
            """;

    @Override
    public List<PrimaryKeyMetadata> listPrimaryKeys(List<ObjectRef> relations) throws SQLException {
        var first = relations.get(0);
        return groupPrimaryKeys(queryKeys(SQL_PRIMARY_KEYS, relations, first.schema()),
                new RelationScope(first.catalog(), first.schema()));
    }

    @Override
    public List<ForeignKeyMetadata> listForeignKeys(List<ObjectRef> relations) throws SQLException {
        var first = relations.get(0);
        return groupForeignKeys(queryKeys(SQL_FOREIGN_KEYS, relations, first.schema()),
                new RelationScope(first.catalog(), first.schema()));
    }

    @Override
    protected String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
