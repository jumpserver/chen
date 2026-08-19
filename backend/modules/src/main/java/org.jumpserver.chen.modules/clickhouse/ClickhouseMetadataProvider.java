package org.jumpserver.chen.modules.clickhouse;

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
import java.util.Locale;
import java.util.Set;

public class ClickhouseMetadataProvider extends BaseDatabaseMetadataProvider {

    public ClickhouseMetadataProvider(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    private static final MetadataCapabilities CAPABILITIES = new MetadataCapabilities(
            false, true, true, true, true, false, false, true, true,
            true, true, true, false, false, true, true
    );

    private static final String VIEW_ENGINES = "'View', 'MaterializedView', 'LiveView', 'WindowView'";

    private static final String SQL_SCHEMAS = "SELECT name AS name FROM system.databases";

    private static final String SQL_TABLES = """
            SELECT name,
                   engine,
                   NULL AS character_set,
                   NULL AS collation,
                   comment
            FROM system.tables
            WHERE database = ? AND is_temporary = 0 AND engine NOT IN (%s)
            ORDER BY name
            """.formatted(VIEW_ENGINES);

    private static final String SQL_TABLE_STATS = """
            SELECT name,
                   total_rows AS estimated_rows,
                   total_bytes AS total_size_bytes
            FROM system.tables
            WHERE database = ? AND is_temporary = 0 AND engine NOT IN (%s)
            ORDER BY name
            """.formatted(VIEW_ENGINES);

    private static final String SQL_VIEWS = """
            SELECT name, engine AS type, comment
            FROM system.tables
            WHERE database = ? AND is_temporary = 0 AND engine IN (%s)
            ORDER BY name
            """.formatted(VIEW_ENGINES);

    private static final String SQL_INDEXES = """
            SELECT name,
                   table AS table_name,
                   NULL AS column_name,
                   expr AS expression,
                   NULL AS is_unique,
                   type AS method
            FROM system.data_skipping_indices
            WHERE database = ?
            ORDER BY table, name
            """;

    private static final String SQL_COLUMNS = """
            SELECT name AS name, table AS table_name, type AS native_type, NULL AS nullable
            FROM system.columns
            WHERE database = ? AND table IN (__IN__)
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
