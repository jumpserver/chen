package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.CatalogMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.ScopeKind;
import org.jumpserver.chen.framework.datasource.metadata.ScopeProperties;
import org.jumpserver.chen.framework.datasource.metadata.ScopeRef;
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

public class SQLServerMetadataProvider extends BaseDatabaseMetadataProvider {

    public SQLServerMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            true, true, true, true, true, true, true, true, false,
            true, true, false, false, false, true, true
    );

    private static final String SQL_CATALOGS = "SELECT name AS name FROM sys.databases";

    private static final String SQL_SCHEMAS = "SELECT schema_name AS name FROM INFORMATION_SCHEMA.SCHEMATA";

    private static final String SQL_TABLES = """
            SELECT t.name AS name,
                   NULL AS engine,
                   NULL AS character_set,
                   NULL AS collation,
                   CONVERT(nvarchar(max), ep.value) AS comment
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ?
            ORDER BY t.name
            """;

    private static final String SQL_TABLE_STATS = """
            WITH row_counts AS (
                SELECT object_id, SUM(rows) AS estimated_rows
                FROM sys.partitions
                WHERE index_id IN (0, 1)
                GROUP BY object_id
            ), allocated_sizes AS (
                SELECT p.object_id, SUM(CONVERT(bigint, a.total_pages)) * 8192 AS total_size_bytes
                FROM sys.partitions p
                JOIN sys.allocation_units a ON a.container_id IN (p.hobt_id, p.partition_id)
                GROUP BY p.object_id
            )
            SELECT t.name AS name,
                   r.estimated_rows AS estimated_rows,
                   z.total_size_bytes AS total_size_bytes
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN row_counts r ON r.object_id = t.object_id
            LEFT JOIN allocated_sizes z ON z.object_id = t.object_id
            WHERE s.name = ?
            ORDER BY t.name
            """;

    private static final String SQL_VIEWS = """
            SELECT v.name AS name,
                   'VIEW' AS type,
                   CONVERT(nvarchar(max), ep.value) AS comment
            FROM sys.views v
            JOIN sys.schemas s ON s.schema_id = v.schema_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = v.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ?
            ORDER BY v.name
            """;

    private static final String SQL_INDEXES = """
            SELECT i.name AS name,
                   t.name AS table_name,
                   c.name AS column_name,
                   i.is_unique AS is_unique,
                   i.type_desc AS method,
                   NULL AS definition,
                   ic.is_included_column AS included
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.index_columns ic
              ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            LEFT JOIN sys.columns c
              ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ? AND i.name IS NOT NULL AND i.is_hypothetical = 0
            ORDER BY t.name, i.name, ic.is_included_column, ic.key_ordinal, ic.index_column_id
            """;

    private static final String SQL_COLUMNS = """
            SELECT c.name AS name, o.name AS table_name, c.column_id AS ordinal,
                   ty.name AS native_type,
                   CASE WHEN ty.name IN ('timestamp', 'rowversion') THEN 'binary' ELSE ty.name END AS jdbc_type_name,
                   CASE
                     WHEN c.max_length = -1 THEN 2147483647
                     WHEN ty.name IN ('nchar', 'nvarchar') AND c.max_length > 0 THEN c.max_length / 2
                     WHEN ty.name IN ('char', 'varchar', 'binary', 'varbinary') THEN c.max_length
                     WHEN ty.name IN ('decimal', 'numeric') THEN c.precision
                     ELSE NULL
                   END AS size,
                   CASE WHEN ty.name IN ('decimal', 'numeric', 'time', 'datetime2', 'datetimeoffset')
                        THEN c.scale ELSE NULL END AS scale,
                   c.is_nullable AS nullable, dc.definition AS default_value,
                   CONVERT(nvarchar(max), ep.value) AS comment
            FROM sys.columns c
            JOIN sys.objects o ON o.object_id = c.object_id AND o.type IN ('U', 'V')
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
            WHERE s.name = ? AND o.name IN (__IN__)
            ORDER BY o.name, c.column_id
            """;

    private static final String SQL_DATABASE_PROPERTIES = """
            SELECT name, collation_name, state_desc, recovery_model_desc, compatibility_level, create_date
            FROM sys.databases
            WHERE name = ?
            """;

    @Override
    public MetadataCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<CatalogMetadata> listCatalogs() throws SQLException {
        var result = new ArrayList<CatalogMetadata>();
        for (var row : query(SQL_CATALOGS, List.of())) {
            result.add(new CatalogMetadata(stringValue(row, "name")));
        }
        return result;
    }

    @Override
    public List<SchemaMetadata> listSchemas(String catalog) throws SQLException {
        var result = new ArrayList<SchemaMetadata>();
        for (var row : query(SQL_SCHEMAS, List.of())) {
            result.add(new SchemaMetadata(catalog, stringValue(row, "name")));
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

    @Override
    public ScopeProperties scopeProperties(ScopeRef ref) throws SQLException {
        if (ref.kind() != ScopeKind.CATALOG) {
            return super.scopeProperties(ref);
        }
        return loadScopeProperties(ref, SQL_DATABASE_PROPERTIES, List.of(ref.scope().catalog()));
    }

    private static final String SQL_PRIMARY_KEYS = """
            SELECT t.name AS table_name, c.name AS column_name, kc.name AS name
            FROM sys.key_constraints kc
            JOIN sys.tables t ON t.object_id = kc.parent_object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = t.object_id AND ic.index_id = kc.unique_index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE kc.type = 'PK' AND s.name = ? AND t.name IN (__IN__)
            ORDER BY t.name, kc.name, ic.key_ordinal
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT t.name AS table_name, c.name AS column_name, fk.name AS name,
                   rs.name AS referenced_schema, rt.name AS referenced_table, rc.name AS referenced_column
            FROM sys.foreign_keys fk
            JOIN sys.tables t ON t.object_id = fk.parent_object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.columns c ON c.object_id = fkc.parent_object_id AND c.column_id = fkc.parent_column_id
            JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
            JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
            JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
            WHERE s.name = ? AND t.name IN (__IN__)
            ORDER BY t.name, fk.name, fkc.constraint_column_id
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
