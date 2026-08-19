package org.jumpserver.chen.framework.datasource.metadata;

import org.jumpserver.chen.framework.datasource.ConnectionManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared plumbing for dialect providers: the parameterized query entry point,
 * dialect identifier quoting, IN-list chunking, and canonical row-mapping helpers.
 */
public abstract class BaseDatabaseMetadataProvider implements DatabaseMetadataProvider {

    protected final ConnectionManager connectionManager;

    protected BaseDatabaseMetadataProvider(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * The only SQL execution primitive providers should use. All identifiers in
     * value position must be passed as {@code ?} parameters; identifiers in
     * identifier position must be {@link #quoteIdentifier(String)}-quoted and
     * already canonical-validated by the caller.
     */
    protected List<Map<String, Object>> query(String sql, List<?> parameters) throws SQLException {
        return this.connectionManager.getSqlActuator().queryRows(sql, parameters);
    }

    /**
     * Dialect-specific identifier quoting. Default is SQL-standard double quotes.
     * Override for dialects that use a different quote (e.g. backticks).
     */
    protected String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Split a name list into sub-batches that respect an IN-clause bound. */
    protected static List<List<String>> chunk(List<String> values, int bound) {
        var chunks = new ArrayList<List<String>>();
        for (int i = 0; i < values.size(); i += bound) {
            chunks.add(values.subList(i, Math.min(values.size(), i + bound)));
        }
        return chunks;
    }

    // -- shared row mapping -------------------------------------------------

    protected static String stringValue(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    protected static Long longValue(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static Boolean booleanValue(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value == null) {
            return null;
        }
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "true", "t", "yes", "y", "1" -> true;
            case "false", "f", "no", "n", "0" -> false;
            default -> null;
        };
    }

    /**
     * Groups flat index rows (one row per column) into {@link IndexMetadata},
     * preserving column order and folding the shared definition onto the index.
     */
    protected List<IndexMetadata> groupIndexRows(List<Map<String, Object>> rows, RelationScope scope) {
        var grouped = new LinkedHashMap<String, MutableIndex>();
        for (var row : rows) {
            var name = stringValue(row, "name");
            var table = stringValue(row, "table_name");
            var key = table + "\u0000" + name;
            var index = grouped.computeIfAbsent(key, ignored -> new MutableIndex(
                    name,
                    table,
                    booleanValue(row, "is_unique"),
                    stringValue(row, "method"),
                    stringValue(row, "definition")
            ));
            var column = stringValue(row, "column_name");
            var expression = stringValue(row, "expression");
            if (column != null && !column.isBlank()) {
                var included = Boolean.TRUE.equals(booleanValue(row, "included"));
                index.parts.add(new IndexPart(index.parts.size(), column, null, null, included));
            } else if (expression != null && !expression.isBlank()) {
                index.parts.add(new IndexPart(index.parts.size(), null, expression, null, false));
            }
            if (index.definition == null) {
                index.definition = stringValue(row, "definition");
            }
        }
        var result = new ArrayList<IndexMetadata>(grouped.size());
        for (var index : grouped.values()) {
            result.add(new IndexMetadata(
                    index.name,
                    new ObjectRef(scope.catalog(), scope.schema(), index.table, RelationKind.TABLE),
                    Boolean.TRUE.equals(index.unique),
                    index.method,
                    List.copyOf(index.parts),
                    index.definition
            ));
        }
        return result;
    }

    /**
     * Shared batched column load. Groups relations by schema, chunks the name
     * list to respect IN-clause limits, and reassociates each column with its
     * owner via the {@code table_name} projection in {@code sqlTemplate}.
     *
     * <p>{@code sqlTemplate} must contain a {@code __IN__} marker where the
     * (comma-separated) {@code ?} placeholders are inserted, and project the
     * canonical aliases {@code name}/{@code table_name}/{@code native_type}/
     * {@code nullable}.</p>
     */
    protected List<ColumnMetadata> loadColumns(String sqlTemplate, List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<ColumnMetadata>();
        var bySchema = new LinkedHashMap<String, List<ObjectRef>>();
        for (var relation : relations) {
            bySchema.computeIfAbsent(relation.schema(), ignored -> new ArrayList<>()).add(relation);
        }
        for (var entry : bySchema.entrySet()) {
            var schema = entry.getKey();
            var refs = entry.getValue();
            var refByName = new HashMap<String, ObjectRef>();
            for (var ref : refs) {
                refByName.put(ref.name(), ref);
            }
            var names = refs.stream().map(ObjectRef::name).toList();
            for (var batch : chunk(names, 500)) {
                var placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                var params = new ArrayList<Object>();
                params.add(schema);
                params.addAll(batch);
                var sql = sqlTemplate.replace("__IN__", placeholders);
                for (var row : query(sql, params)) {
                    var owner = refByName.get(stringValue(row, "table_name"));
                    if (owner == null) {
                        continue;
                    }
                    result.add(new ColumnMetadata(
                            owner,
                            stringValue(row, "name"),
                            0,
                            stringValue(row, "native_type"),
                            0,
                            Boolean.TRUE.equals(booleanValue(row, "nullable")),
                            null,
                            null
                    ));
                }
            }
        }
        return result;
    }

    // -- defaults -----------------------------------------------------------

    @Override
    public List<CatalogMetadata> listCatalogs() throws SQLException {
        return List.of();
    }

    @Override
    public List<SchemaMetadata> listSchemas(String catalog) throws SQLException {
        return List.of();
    }

    @Override
    public List<RelationMetadata> listRelations(RelationScope scope, Set<RelationKind> kinds) throws SQLException {
        throw unsupported("relations");
    }

    @Override
    public List<RelationMetadata> searchRelations(RelationScope scope, Set<RelationKind> kinds, String prefix, int limit)
            throws SQLException {
        var relations = this.listRelations(scope, kinds);
        var normalizedPrefix = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        var stream = relations.stream()
                .filter(relation -> normalizedPrefix.isEmpty()
                        || relation.ref().name().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        || (relation.ref().schema() + "." + relation.ref().name())
                        .toLowerCase(Locale.ROOT).startsWith(normalizedPrefix));
        if (limit > 0) {
            stream = stream.limit(limit);
        }
        return stream.toList();
    }

    @Override
    public List<ColumnMetadata> listColumns(List<ObjectRef> relations) throws SQLException {
        throw unsupported("columns");
    }

    @Override
    public List<IndexMetadata> listIndexes(RelationScope scope) throws SQLException {
        throw unsupported("indexes");
    }

    @Override
    public List<ObjectStatistics> listStatistics(RelationScope scope) throws SQLException {
        throw unsupported("statistics");
    }

    @Override
    public List<PrimaryKeyMetadata> listPrimaryKeys(List<ObjectRef> relations) throws SQLException {
        throw unsupported("primary keys");
    }

    @Override
    public List<ForeignKeyMetadata> listForeignKeys(List<ObjectRef> relations) throws SQLException {
        throw unsupported("foreign keys");
    }

    @Override
    public String getSchemaDefinition(RelationScope scope) throws SQLException {
        return null;
    }

    @Override
    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        return new ObjectProperties(ref, List.of());
    }

    /**
     * Runs a single-row vendor properties query and maps its first row to
     * {@link PropertyItem}s in column order.
     */
    protected ObjectProperties loadObjectProperties(ObjectRef ref, String sql) throws SQLException {
        var rows = query(sql, List.of(ref.schema(), ref.name()));
        if (rows.isEmpty()) {
            return new ObjectProperties(ref, List.of());
        }
        var items = new ArrayList<PropertyItem>();
        for (var entry : rows.get(0).entrySet()) {
            var value = entry.getValue();
            items.add(new PropertyItem(entry.getKey(), value == null ? null : String.valueOf(value)));
        }
        return new ObjectProperties(ref, items);
    }

    /**
     * Runs a key query whose rows are (table_name, column_name, name[, referenced_*])
     * restricted to {@code table IN (…)}, and groups them by owner.
     */
    protected List<Map<String, Object>> queryKeys(String sqlTemplate, List<ObjectRef> relations, String schema)
            throws SQLException {
        if (relations.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var batch : chunk(relations.stream().map(ObjectRef::name).toList(), 500)) {
            var placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            var params = new ArrayList<Object>();
            params.add(schema);
            params.addAll(batch);
            result.addAll(query(sqlTemplate.replace("__IN__", placeholders), params));
        }
        return result;
    }

    protected List<PrimaryKeyMetadata> groupPrimaryKeys(List<Map<String, Object>> rows, RelationScope scope) {
        var grouped = new LinkedHashMap<String, MutablePrimaryKey>();
        for (var row : rows) {
            var table = stringValue(row, "table_name");
            var pk = grouped.computeIfAbsent(table, ignored -> new MutablePrimaryKey(stringValue(row, "name"), table));
            var column = stringValue(row, "column_name");
            if (column != null && !column.isBlank()) {
                pk.columns.add(column);
            }
        }
        var result = new ArrayList<PrimaryKeyMetadata>(grouped.size());
        for (var pk : grouped.values()) {
            result.add(new PrimaryKeyMetadata(
                    new ObjectRef(scope.catalog(), scope.schema(), pk.table, RelationKind.TABLE),
                    pk.name,
                    List.copyOf(pk.columns)
            ));
        }
        return result;
    }

    protected List<ForeignKeyMetadata> groupForeignKeys(List<Map<String, Object>> rows, RelationScope scope) {
        var grouped = new LinkedHashMap<String, MutableForeignKey>();
        for (var row : rows) {
            var table = stringValue(row, "table_name");
            var name = stringValue(row, "name");
            var fk = grouped.computeIfAbsent(table + " " + name, ignored -> new MutableForeignKey(name, table));
            fk.columns.add(stringValue(row, "column_name"));
            fk.referencedSchema = stringValue(row, "referenced_schema");
            fk.referencedTable = stringValue(row, "referenced_table");
            fk.referencedColumns.add(stringValue(row, "referenced_column"));
        }
        var result = new ArrayList<ForeignKeyMetadata>(grouped.size());
        for (var fk : grouped.values()) {
            result.add(new ForeignKeyMetadata(
                    new ObjectRef(scope.catalog(), scope.schema(), fk.table, RelationKind.TABLE),
                    fk.name,
                    List.copyOf(fk.columns),
                    new ObjectRef(scope.catalog(), fk.referencedSchema, fk.referencedTable, RelationKind.TABLE),
                    List.copyOf(fk.referencedColumns)
            ));
        }
        return result;
    }

    private static final class MutablePrimaryKey {
        private final String name;
        private final String table;
        private final List<String> columns = new ArrayList<>();

        private MutablePrimaryKey(String name, String table) {
            this.name = name;
            this.table = table;
        }
    }

    private static final class MutableForeignKey {
        private final String name;
        private final String table;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();
        private String referencedSchema;
        private String referencedTable;

        private MutableForeignKey(String name, String table) {
            this.name = name;
            this.table = table;
        }
    }

    private static UnsupportedOperationException unsupported(String category) {
        return new UnsupportedOperationException("Metadata category not supported: " + category);
    }

    private static final class MutableIndex {
        private final String name;
        private final String table;
        private final Boolean unique;
        private final String method;
        private String definition;
        private final List<IndexPart> parts = new ArrayList<>();

        private MutableIndex(String name, String table, Boolean unique, String method, String definition) {
            this.name = name;
            this.table = table;
            this.unique = unique;
            this.method = method;
            this.definition = definition;
        }
    }
}
