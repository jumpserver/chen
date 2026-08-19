package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.ObjectStatistics;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.SchemaMetadata;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MysqlMetadataProvider extends BaseDatabaseMetadataProvider {

    public MysqlMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, false, false, true, true,
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
            SELECT COLUMN_NAME AS name, TABLE_NAME AS table_name, COLUMN_TYPE AS native_type,
                   IS_NULLABLE AS nullable
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (__IN__)
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
        if (kinds.contains(RelationKind.VIEW) || kinds.contains(RelationKind.MATERIALIZED_VIEW)) {
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

    @Override
    protected String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
