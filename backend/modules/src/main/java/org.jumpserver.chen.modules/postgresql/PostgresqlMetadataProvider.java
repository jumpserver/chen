package org.jumpserver.chen.modules.postgresql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.CatalogMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.IndexMetadata;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCapabilities;
import org.jumpserver.chen.framework.datasource.metadata.ObjectProperties;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.ObjectStatistics;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.SchemaMetadata;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PostgresqlMetadataProvider extends BaseDatabaseMetadataProvider {

    public PostgresqlMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            true, true, true, true, true, false, false, true, false,
            true, true, false, false, false, true, true
    );

    private static final String SQL_CATALOGS = "SELECT DATNAME AS name FROM PG_DATABASE WHERE DATISTEMPLATE = FALSE";

    private static final String SQL_SCHEMAS = "SELECT SCHEMA_NAME AS name FROM INFORMATION_SCHEMA.SCHEMATA";

    private static final String SQL_TABLES = """
            SELECT c.relname AS name,
                   NULL::text AS character_set,
                   NULL::text AS collation,
                   obj_description(c.oid, 'pg_class') AS comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
            ORDER BY c.relname
            """;

    private static final String SQL_TABLE_STATS = """
            SELECT c.relname AS name,
                   c.reltuples::bigint AS estimated_rows,
                   pg_total_relation_size(c.oid) AS total_size_bytes
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
            ORDER BY c.relname
            """;

    private static final String SQL_VIEWS = """
            SELECT c.relname AS name,
                   CASE c.relkind WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'VIEW' END AS type,
                   obj_description(c.oid, 'pg_class') AS comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('v', 'm')
            ORDER BY c.relname
            """;

    private static final String SQL_INDEXES = """
            SELECT ic.relname AS name,
                   tc.relname AS table_name,
                   a.attname AS column_name,
                   CASE WHEN k.attnum = 0 THEN pg_get_indexdef(ic.oid, k.position, true) END AS expression,
                   i.indisunique AS is_unique,
                   am.amname AS method,
                   pg_get_indexdef(ic.oid) AS definition
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_class tc ON tc.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = tc.relnamespace
            JOIN pg_am am ON am.oid = ic.relam
            LEFT JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, position) ON true
            LEFT JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = k.attnum AND k.attnum > 0
            WHERE n.nspname = ?
            ORDER BY tc.relname, ic.relname, k.position
            """;

    private static final String SQL_COLUMNS = """
            SELECT COLUMN_NAME AS name, TABLE_NAME AS table_name, DATA_TYPE AS native_type,
                   IS_NULLABLE AS nullable
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (__IN__)
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
        if (kinds.contains(RelationKind.VIEW) || kinds.contains(RelationKind.MATERIALIZED_VIEW)) {
            for (var row : query(SQL_VIEWS, List.of(scope.schema()))) {
                var type = stringValue(row, "type");
                var kind = type != null && type.toUpperCase(Locale.ROOT).contains("MATERIALIZED")
                        ? RelationKind.MATERIALIZED_VIEW
                        : RelationKind.VIEW;
                if (!kinds.contains(kind)) {
                    continue;
                }
                result.add(new RelationMetadata(
                        new ObjectRef(scope.catalog(), scope.schema(), stringValue(row, "name"), kind),
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
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?
            """;

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return loadObjectProperties(ref, SQL_TABLE_PROPERTIES);
    }
}
