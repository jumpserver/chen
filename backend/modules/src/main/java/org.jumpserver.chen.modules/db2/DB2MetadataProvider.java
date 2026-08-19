package org.jumpserver.chen.modules.db2;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
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

public class DB2MetadataProvider extends BaseDatabaseMetadataProvider {

    public DB2MetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, true, true, true, false,
            true, true, false, true, false, true, true
    );

    private static final String SQL_SCHEMAS = "SELECT SCHEMANAME AS name FROM syscat.schemata";

    private static final String SQL_TABLES = """
            SELECT RTRIM(t.tabname) AS name,
                   NULL AS engine,
                   CHAR(t.codepage) AS character_set,
                   NULL AS collation,
                   t.remarks AS comment
            FROM syscat.tables t
            WHERE t.tabschema = ? AND t.type = 'T'
            ORDER BY t.tabname
            """;

    private static final String SQL_TABLE_STATS = """
            WITH index_pages AS (
                SELECT tabschema, tabname, SUM(COALESCE(nleaf, 0)) AS pages
                FROM syscat.indexes
                GROUP BY tabschema, tabname
            )
            SELECT RTRIM(t.tabname) AS name,
                   t.card AS estimated_rows,
                   (COALESCE(t.npages, 0) + COALESCE(i.pages, 0)) * ts.pagesize AS total_size_bytes
            FROM syscat.tables t
            LEFT JOIN syscat.tablespaces ts ON ts.tbspaceid = t.tbspaceid
            LEFT JOIN index_pages i ON i.tabschema = t.tabschema AND i.tabname = t.tabname
            WHERE t.tabschema = ? AND t.type = 'T'
            ORDER BY t.tabname
            """;

    private static final String SQL_VIEWS = """
            SELECT RTRIM(tabname) AS name, 'VIEW' AS type, remarks AS comment
            FROM syscat.tables
            WHERE tabschema = ? AND type = 'V'
            ORDER BY tabname
            """;

    private static final String SQL_INDEXES = """
            SELECT RTRIM(i.indname) AS name,
                   RTRIM(i.tabname) AS table_name,
                   RTRIM(c.colname) AS column_name,
                   CASE WHEN i.uniquerule IN ('P', 'U') THEN 1 ELSE 0 END AS is_unique,
                   i.indextype AS method,
                   NULL AS definition
            FROM syscat.indexes i
            LEFT JOIN syscat.indexcoluse c
              ON c.indschema = i.indschema AND c.indname = i.indname
            WHERE i.tabschema = ?
            ORDER BY i.tabname, i.indname, c.colseq
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLNAME AS name, TABNAME AS table_name, TYPENAME AS native_type,
                   NULLS AS nullable
            FROM syscat.COLUMNS
            WHERE TABSCHEMA = ? AND TABNAME IN (__IN__)
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
                        stringValue(row, "character_set"),
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

    private static final String SQL_TABLE_PROPERTIES = """
            SELECT TABNAME, TBSPACE, TABSCHEMA, TYPE, STATUS, COLCOUNT, ACTIVE_BLOCKS, AVGROWSIZE, OWNER, CREATE_TIME
            FROM syscat.TABLES
            WHERE TABSCHEMA = ? AND TABNAME = ? AND TBSPACE IS NOT NULL
            """;

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return loadObjectProperties(ref, SQL_TABLE_PROPERTIES);
    }

    private static final String SQL_PRIMARY_KEYS = """
            SELECT RTRIM(k.tabname) AS table_name, RTRIM(k.colname) AS column_name, RTRIM(t.constname) AS name
            FROM syscat.tabconst t
            JOIN syscat.keycoluse k ON k.tabschema = t.tabschema AND k.tabname = t.tabname AND k.constname = t.constname
            WHERE t.type = 'P' AND t.tabschema = ? AND t.tabname IN (__IN__)
            ORDER BY t.tabname, t.constname, k.colseq
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT RTRIM(f.tabname) AS table_name, RTRIM(k.colname) AS column_name, RTRIM(f.constname) AS name,
                   RTRIM(f.ref_tabschema) AS referenced_schema, RTRIM(f.reftabname) AS referenced_table,
                   RTRIM(rk.colname) AS referenced_column
            FROM syscat.references f
            JOIN syscat.keycoluse k ON k.tabschema = f.tabschema AND k.tabname = f.tabname AND k.constname = f.constname
            JOIN syscat.keycoluse rk ON rk.tabschema = f.ref_tabschema AND rk.tabname = f.reftabname
                 AND rk.constname = f.constname AND rk.colseq = k.colseq
            WHERE f.tabschema = ? AND f.tabname IN (__IN__)
            ORDER BY f.tabname, f.constname, k.colseq
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
}
