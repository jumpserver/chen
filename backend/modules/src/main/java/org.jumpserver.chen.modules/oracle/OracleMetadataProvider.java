package org.jumpserver.chen.modules.oracle;

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

public class OracleMetadataProvider extends BaseDatabaseMetadataProvider {

    public OracleMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, false, false, true, false,
            true, true, false, false, false, true, true
    );

    private static final String SQL_SCHEMAS = "SELECT USERNAME AS name FROM ALL_USERS";

    private static final String SQL_TABLES = """
            SELECT t.table_name AS name,
                   NULL AS engine,
                   NULL AS character_set,
                   NULL AS collation,
                   c.comments AS comment
            FROM all_tables t
            LEFT JOIN all_tab_comments c
              ON c.owner = t.owner AND c.table_name = t.table_name AND c.table_type = 'TABLE'
            WHERE t.owner = ? AND t.tablespace_name IS NOT NULL
            ORDER BY t.table_name
            """;

    private static final String SQL_TABLE_STATS = """
            WITH target_schema AS (SELECT ? AS owner FROM dual), object_segments AS (
                SELECT s.segment_name AS table_name, s.bytes
                FROM all_segments s JOIN target_schema x ON x.owner = s.owner
                WHERE s.segment_type IN ('TABLE', 'TABLE PARTITION', 'TABLE SUBPARTITION')
                UNION ALL
                SELECT i.table_name, s.bytes
                FROM all_segments s
                JOIN all_indexes i ON i.owner = s.owner AND i.index_name = s.segment_name
                JOIN target_schema x ON x.owner = i.owner
                WHERE s.segment_type IN ('INDEX', 'INDEX PARTITION', 'INDEX SUBPARTITION')
                UNION ALL
                SELECT l.table_name, s.bytes
                FROM all_segments s
                JOIN all_lobs l ON l.owner = s.owner AND s.segment_name IN (l.segment_name, l.index_name)
                JOIN target_schema x ON x.owner = l.owner
            ), object_sizes AS (
                SELECT table_name, SUM(bytes) AS total_size_bytes
                FROM object_segments GROUP BY table_name
            )
            SELECT t.table_name AS name,
                   t.num_rows AS estimated_rows,
                   z.total_size_bytes AS total_size_bytes
            FROM all_tables t
            JOIN target_schema x ON x.owner = t.owner
            LEFT JOIN object_sizes z ON z.table_name = t.table_name
            WHERE t.tablespace_name IS NOT NULL
            ORDER BY t.table_name
            """;

    private static final String SQL_VIEWS = """
            SELECT v.view_name AS name, 'VIEW' AS type, c.comments AS comment
            FROM all_views v
            LEFT JOIN all_tab_comments c
              ON c.owner = v.owner AND c.table_name = v.view_name AND c.table_type = 'VIEW'
            WHERE v.owner = ?
            ORDER BY v.view_name
            """;

    private static final String SQL_INDEXES = """
            SELECT i.index_name AS name,
                   i.table_name AS table_name,
                   CASE WHEN e.column_expression IS NULL THEN c.column_name END AS column_name,
                   e.column_expression AS expression,
                   CASE WHEN i.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                   i.index_type AS method
            FROM all_indexes i
            JOIN all_ind_columns c
              ON c.index_owner = i.owner AND c.index_name = i.index_name AND c.table_owner = i.table_owner
            LEFT JOIN all_ind_expressions e
              ON e.index_owner = c.index_owner AND e.index_name = c.index_name
             AND e.table_owner = c.table_owner AND e.column_position = c.column_position
            WHERE i.owner = ?
            ORDER BY i.table_name, i.index_name, c.column_position
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLUMN_NAME AS name, TABLE_NAME AS table_name, DATA_TYPE AS native_type,
                   NULLABLE AS nullable
            FROM ALL_TAB_COLUMNS
            WHERE OWNER = ? AND TABLE_NAME IN (__IN__)
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
                        null,
                        null,
                        null
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
}
