package org.jumpserver.chen.modules.postgresql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.BaseDatabaseMetadataProvider;
import org.jumpserver.chen.framework.datasource.metadata.CatalogMetadata;
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
import org.jumpserver.chen.framework.datasource.metadata.ScopeKind;
import org.jumpserver.chen.framework.datasource.metadata.ScopeProperties;
import org.jumpserver.chen.framework.datasource.metadata.ScopeRef;

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
            true, true, true, true, true, true, true, true, false,
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
            SELECT c.column_name AS name, c.table_name AS table_name, c.ordinal_position AS ordinal,
                   CASE WHEN c.data_type IN ('USER-DEFINED', 'ARRAY') THEN c.udt_name ELSE c.data_type END AS native_type,
                   c.data_type AS jdbc_type_name,
                   COALESCE(c.character_maximum_length, c.numeric_precision, c.datetime_precision) AS size,
                   c.numeric_scale AS scale, c.is_nullable AS nullable,
                   c.column_default AS default_value, col_description(pc.oid, a.attnum) AS comment
            FROM information_schema.columns c
            LEFT JOIN pg_namespace n ON n.nspname = c.table_schema
            LEFT JOIN pg_class pc ON pc.relnamespace = n.oid AND pc.relname = c.table_name
            LEFT JOIN pg_attribute a ON a.attrelid = pc.oid AND a.attname = c.column_name
            WHERE c.table_schema = ? AND c.table_name IN (__IN__)
            ORDER BY c.table_name, c.ordinal_position
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

    private static final String SQL_DATABASE_PROPERTIES = """
            SELECT datname, pg_database_size(datname) AS size,
                   pg_size_pretty(pg_database_size(datname)) AS size_pretty,
                   datcollate, datctype, datistemplate, datallowconn, datconnlimit
            FROM pg_database
            WHERE datname = ?
            """;

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return loadObjectProperties(ref, SQL_TABLE_PROPERTIES);
    }

    @Override
    public ScopeProperties scopeProperties(ScopeRef ref) throws SQLException {
        if (ref.kind() != ScopeKind.CATALOG) {
            return super.scopeProperties(ref);
        }
        return loadScopeProperties(ref, SQL_DATABASE_PROPERTIES, List.of(ref.scope().catalog()));
    }

    private static final String SQL_PRIMARY_KEYS = """
            SELECT tc.relname AS table_name, a.attname AS column_name, con.conname AS name
            FROM pg_constraint con
            JOIN pg_class tc ON tc.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = tc.relnamespace
            JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = k.attnum
            WHERE con.contype = 'p' AND n.nspname = ? AND tc.relname IN (__IN__)
            ORDER BY tc.relname, con.conname, k.ord
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT tc.relname AS table_name, a.attname AS column_name, con.conname AS name,
                   ns.nspname AS referenced_schema, rc.relname AS referenced_table, ra.attname AS referenced_column
            FROM pg_constraint con
            JOIN pg_class tc ON tc.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = tc.relnamespace
            JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = k.attnum
            JOIN LATERAL unnest(con.confkey) WITH ORDINALITY rk(attnum, ord) ON rk.ord = k.ord
            JOIN pg_class rc ON rc.oid = con.confrelid
            JOIN pg_namespace ns ON ns.oid = rc.relnamespace
            JOIN pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = rk.attnum
            WHERE con.contype = 'f' AND n.nspname = ? AND tc.relname IN (__IN__)
            ORDER BY tc.relname, con.conname, k.ord
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
