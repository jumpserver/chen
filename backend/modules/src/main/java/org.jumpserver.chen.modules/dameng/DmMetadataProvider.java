package org.jumpserver.chen.modules.dameng;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ConstraintMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.ObjectProperties;
import org.jumpserver.chen.framework.datasource.metadata.ForeignKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.PrimaryKeyMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ObjectStatistics;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.SchemaMetadata;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DmMetadataProvider extends BaseDatabaseMetadataProvider {

    public DmMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, true, true, true, false,
            true, false, false, false, false, true, true, true, true
    );

    private static final String SQL_SCHEMAS = "SELECT NAME AS name FROM SYSOBJECTS t WHERE t.TYPE$ ='SCH'";

    private static final String SQL_TABLES = """
            SELECT t.table_name AS name,
                   NULL AS engine,
                   NULL AS character_set,
                   NULL AS collation,
                   c.comments AS comment
            FROM all_tables t
            LEFT JOIN all_tab_comments c
              ON c.owner = t.owner AND c.table_name = t.table_name AND c.table_type = 'TABLE'
            WHERE t.owner = ?
            ORDER BY t.table_name
            """;

    private static final String SQL_TABLE_STATS = """
            SELECT t.table_name AS name,
                   t.num_rows AS estimated_rows,
                   NULL AS total_size_bytes
            FROM all_tables t
            WHERE t.owner = ?
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
                   i.index_type AS method,
                   c.descend AS sort_order
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
            SELECT c.COLUMN_NAME AS name, c.TABLE_NAME AS table_name, c.COLUMN_ID AS ordinal,
                   c.DATA_TYPE AS native_type, c.DATA_TYPE AS jdbc_type_name,
                   COALESCE(c.CHAR_LENGTH, c.DATA_PRECISION, c.DATA_LENGTH) AS size,
                   c.DATA_SCALE AS scale, c.NULLABLE AS nullable,
                   c.DATA_DEFAULT AS default_value, cc.COMMENTS AS comment
            FROM ALL_TAB_COLUMNS c
            LEFT JOIN ALL_COL_COMMENTS cc
              ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME
            WHERE c.OWNER = ? AND c.TABLE_NAME IN (__IN__)
            ORDER BY c.TABLE_NAME, c.COLUMN_ID
            """;

    private static final String SQL_TABLE_INDEXES = """
            SELECT i.index_name AS name, i.table_name AS table_name,
                   CASE WHEN e.column_expression IS NULL THEN c.column_name END AS column_name,
                   e.column_expression AS expression,
                   CASE WHEN i.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                   i.index_type AS method,
                   c.descend AS sort_order
            FROM all_indexes i
            JOIN all_ind_columns c
              ON c.index_owner = i.owner AND c.index_name = i.index_name AND c.table_owner = i.table_owner
            LEFT JOIN all_ind_expressions e
              ON e.index_owner = c.index_owner AND e.index_name = c.index_name
             AND e.table_owner = c.table_owner AND e.column_position = c.column_position
            WHERE i.owner = ? AND i.table_name IN (__IN__)
            ORDER BY i.table_name, i.index_name, c.column_position
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
    public List<IndexMetadata> listIndexes(List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty()) {
            return List.of();
        }
        var first = relations.get(0);
        return groupIndexRows(queryKeys(SQL_TABLE_INDEXES, relations, first.schema()),
                new RelationScope(first.catalog(), first.schema()));
    }

    @Override
    public String getTableDefinition(ObjectRef relation) throws SQLException {
        var rows = query("SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) AS definition FROM dual",
                List.of(relation.name(), relation.schema()));
        return rows.isEmpty() ? null : stringValue(rows.get(0), "definition");
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

    private static final String SQL_TABLE_PROPERTIES = """
            SELECT table_name, tablespace_name, status, num_rows, blocks, avg_row_len, sample_size, owner
            FROM all_tables
            WHERE owner = ? AND table_name = ?
            """;

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return loadObjectProperties(ref, SQL_TABLE_PROPERTIES);
    }

    private static final String SQL_PRIMARY_KEYS = """
            SELECT c.table_name AS table_name, cc.column_name AS column_name, c.constraint_name AS name
            FROM all_constraints c
            JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
            WHERE c.constraint_type = 'P' AND c.owner = ? AND c.table_name IN (__IN__)
            ORDER BY c.table_name, c.constraint_name, cc.position
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT c.table_name AS table_name, cc.column_name AS column_name, c.constraint_name AS name,
                   rc.owner AS referenced_schema, rc.table_name AS referenced_table, rcc.column_name AS referenced_column
            FROM all_constraints c
            JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
            JOIN all_constraints rc ON rc.owner = c.r_owner AND rc.constraint_name = c.r_constraint_name
            JOIN all_cons_columns rcc ON rcc.owner = rc.owner AND rcc.constraint_name = rc.constraint_name AND rcc.position = cc.position
            WHERE c.constraint_type = 'R' AND c.owner = ? AND c.table_name IN (__IN__)
            ORDER BY c.table_name, c.constraint_name, cc.position
            """;

    private static final String SQL_CONSTRAINTS = """
            SELECT c.table_name AS table_name, c.constraint_name AS name,
                   CASE c.constraint_type WHEN 'P' THEN 'PRIMARY KEY' WHEN 'R' THEN 'FOREIGN KEY'
                        WHEN 'U' THEN 'UNIQUE' WHEN 'C' THEN 'CHECK' END AS constraint_type,
                   cc.column_name AS column_name, rc.owner AS referenced_schema,
                   rc.table_name AS referenced_table, rcc.column_name AS referenced_column,
                   c.search_condition AS definition
            FROM all_constraints c
            LEFT JOIN all_cons_columns cc
              ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
            LEFT JOIN all_constraints rc ON rc.owner = c.r_owner AND rc.constraint_name = c.r_constraint_name
            LEFT JOIN all_cons_columns rcc
              ON rcc.owner = rc.owner AND rcc.constraint_name = rc.constraint_name AND rcc.position = cc.position
            WHERE c.constraint_type IN ('P', 'R', 'U', 'C')
              AND c.owner = ? AND c.table_name IN (__IN__)
            ORDER BY c.table_name, c.constraint_name, cc.position
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
    public List<ConstraintMetadata> listConstraints(List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty()) {
            return List.of();
        }
        var first = relations.get(0);
        return groupConstraints(queryKeys(SQL_CONSTRAINTS, relations, first.schema()),
                new RelationScope(first.catalog(), first.schema()));
    }
}
