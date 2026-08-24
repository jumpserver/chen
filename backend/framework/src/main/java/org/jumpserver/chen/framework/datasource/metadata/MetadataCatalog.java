package org.jumpserver.chen.framework.datasource.metadata;

import org.jumpserver.chen.framework.datasource.ConnectionManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped metadata facade: cache + invalidation + database-context
 * switching over a {@link DatabaseMetadataProvider}. It manages metadata only,
 * not the tree children cache, and does not depend on {@code ResourceBrowser}.
 *
 * <p>Object-scoped categories (columns, keys) are cached per relation and batch
 * the misses into a single provider call; schema-scoped categories (relations,
 * indexes, statistics, definitions) are cached per scope.</p>
 */
public class MetadataCatalog {

    private static final long DEFAULT_TTL_MILLIS = 30 * 60 * 1000L;
    private static final long STATISTICS_TTL_MILLIS = 5 * 60 * 1000L;

    private static final Set<RelationKind> ALL_KINDS = EnumSet.allOf(RelationKind.class);

    private final ConnectionManager connectionManager;
    private final DatabaseMetadataProvider provider;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public MetadataCatalog(ConnectionManager connectionManager, DatabaseMetadataProvider provider) {
        this.connectionManager = Objects.requireNonNull(connectionManager);
        this.provider = Objects.requireNonNull(provider);
    }

    public MetadataCapabilities capabilities() {
        return this.provider.capabilities();
    }

    // -- containers ---------------------------------------------------------

    public List<CatalogMetadata> listCatalogs() throws SQLException {
        var key = new CacheKey(null, null, null, Category.CATALOGS);
        return cachedList(key, DEFAULT_TTL_MILLIS, provider.capabilities().catalogs(), () ->
                provider.listCatalogs());
    }

    public List<SchemaMetadata> listSchemas(String catalog) throws SQLException {
        var key = new CacheKey(catalog, null, null, Category.SCHEMAS);
        return cachedList(key, DEFAULT_TTL_MILLIS, provider.capabilities().schemas(), () ->
                connectionManager.withDatabaseContext(catalog, () -> provider.listSchemas(catalog)));
    }

    // -- relations ----------------------------------------------------------

    public List<RelationMetadata> listRelations(RelationScope scope, Set<RelationKind> kinds) throws SQLException {
        var key = new CacheKey(scope.catalog(), scope.schema(), null, Category.RELATIONS);
        var all = cachedList(key, DEFAULT_TTL_MILLIS, provider.capabilities().relations(), () ->
                connectionManager.withDatabaseContext(scope.catalog(), () -> provider.listRelations(scope, ALL_KINDS)));
        if (all.isEmpty()) {
            return List.of();
        }
        return all.stream().filter(relation -> kinds.contains(relation.ref().kind())).toList();
    }

    public List<RelationMetadata> searchRelations(RelationScope scope, Set<RelationKind> kinds, String prefix, int limit)
            throws SQLException {
        if (!provider.capabilities().relations()) {
            return List.of();
        }
        return connectionManager.withDatabaseContext(scope.catalog(), () ->
                provider.searchRelations(scope, kinds, prefix, limit));
    }

    // -- columns / keys (object-scoped, batch of misses) --------------------

    public List<ColumnMetadata> listColumns(List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty() || !provider.capabilities().columns()) {
            return List.of();
        }
        return loadObjectScoped(relations, Category.COLUMNS, provider::listColumns, DEFAULT_TTL_MILLIS);
    }

    public List<PrimaryKeyMetadata> listPrimaryKeys(List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty() || !provider.capabilities().primaryKeys()) {
            return List.of();
        }
        return loadObjectScoped(relations, Category.PRIMARY_KEYS, provider::listPrimaryKeys, DEFAULT_TTL_MILLIS);
    }

    public List<ForeignKeyMetadata> listForeignKeys(List<ObjectRef> relations) throws SQLException {
        if (relations.isEmpty() || !provider.capabilities().foreignKeys()) {
            return List.of();
        }
        return loadObjectScoped(relations, Category.FOREIGN_KEYS, provider::listForeignKeys, DEFAULT_TTL_MILLIS);
    }

    // -- schema-scoped ------------------------------------------------------

    public List<IndexMetadata> listIndexes(RelationScope scope) throws SQLException {
        var key = new CacheKey(scope.catalog(), scope.schema(), null, Category.INDEXES);
        return cachedList(key, DEFAULT_TTL_MILLIS, provider.capabilities().indexes(), () ->
                connectionManager.withDatabaseContext(scope.catalog(), () -> provider.listIndexes(scope)));
    }

    public List<ObjectStatistics> listStatistics(RelationScope scope) throws SQLException {
        var key = new CacheKey(scope.catalog(), scope.schema(), null, Category.STATISTICS);
        return cachedList(key, STATISTICS_TTL_MILLIS, provider.capabilities().statistics(), () ->
                connectionManager.withDatabaseContext(scope.catalog(), () -> provider.listStatistics(scope)));
    }

    public String getSchemaDefinition(RelationScope scope) throws SQLException {
        var key = new CacheKey(scope.catalog(), scope.schema(), null, Category.DEFINITIONS);
        return cachedNullable(key, DEFAULT_TTL_MILLIS, provider.capabilities().definitions(), () ->
                connectionManager.withDatabaseContext(scope.catalog(), () -> provider.getSchemaDefinition(scope)));
    }

    public ObjectProperties objectProperties(ObjectRef ref) throws SQLException {
        if (!provider.capabilities().relations()) {
            return new ObjectProperties(ref, List.of());
        }
        return connectionManager.withDatabaseContext(ref.catalog(), () -> provider.objectProperties(ref));
    }

    public ScopeProperties scopeProperties(ScopeRef ref) throws SQLException {
        return connectionManager.withDatabaseContext(ref.scope().catalog(), () -> provider.scopeProperties(ref));
    }

    // -- invalidation -------------------------------------------------------

    public void invalidate(RelationScope scope) {
        invalidatePrefix(scope.catalog(), scope.schema());
    }

    public void invalidateCatalog(String catalog) {
        cache.keySet().removeIf(key -> Objects.equals(key.catalog(), catalog));
    }

    public void invalidate(ObjectRef ref) {
        invalidatePrefix(ref.catalog(), ref.schema(), ref.name());
    }

    public void invalidateAll() {
        cache.clear();
    }

    // -- internals ----------------------------------------------------------

    private <T> List<T> cachedList(CacheKey key, long ttlMillis, boolean supported, Loader<List<T>> loader)
            throws SQLException {
        return cached(key, ttlMillis, supported, loader, List.of());
    }

    private <T> T cachedNullable(CacheKey key, long ttlMillis, boolean supported, Loader<T> loader)
            throws SQLException {
        return cached(key, ttlMillis, supported, loader, null);
    }

    private <T> T cached(CacheKey key, long ttlMillis, boolean supported, Loader<T> loader, T emptyValue)
            throws SQLException {
        var now = System.currentTimeMillis();
        var existing = cache.get(key);
        if (existing != null && now - existing.loadedAt() < ttlMillis) {
            if (existing.state() == State.UNSUPPORTED) {
                return emptyValue;
            }
            return cast(existing.value());
        }
        if (!supported) {
            cache.put(key, new CacheEntry(State.UNSUPPORTED, null, now));
            return emptyValue;
        }
        var value = loader.load();
        var state = isEmpty(value) ? State.LOADED_EMPTY : State.LOADED;
        cache.put(key, new CacheEntry(state, value, now));
        return value;
    }

    private <T> List<T> loadObjectScoped(List<ObjectRef> relations, Category category, ObjectLoader<T> loader, long ttlMillis)
            throws SQLException {
        var result = new ArrayList<T>();
        var misses = new ArrayList<ObjectRef>();
        var now = System.currentTimeMillis();
        for (var ref : relations) {
            var key = new CacheKey(ref.catalog(), ref.schema(), ref.name(), category);
            var existing = cache.get(key);
            if (existing != null && existing.state() != State.UNSUPPORTED && now - existing.loadedAt() < ttlMillis) {
                result.addAll(castList(existing.value()));
            } else {
                misses.add(ref);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }

        var missesByScope = new LinkedHashMap<RelationScope, List<ObjectRef>>();
        for (var ref : misses) {
            missesByScope.computeIfAbsent(new RelationScope(ref.catalog(), ref.schema()), ignored -> new ArrayList<>()).add(ref);
        }

        for (var entry : missesByScope.entrySet()) {
            var scope = entry.getKey();
            var scopeRefs = entry.getValue();
            var fetched = connectionManager.withDatabaseContext(scope.catalog(), () -> loader.load(scopeRefs));
            var byOwner = new LinkedHashMap<ObjectRef, List<T>>();
            for (var item : fetched) {
                var owner = ownerOf(item);
                if (owner != null) {
                    byOwner.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(item);
                }
            }
            for (var ref : scopeRefs) {
                var items = byOwner.getOrDefault(ref, List.of());
                var key = new CacheKey(ref.catalog(), ref.schema(), ref.name(), category);
                cache.put(key, new CacheEntry(
                        items.isEmpty() ? State.LOADED_EMPTY : State.LOADED,
                        items,
                        System.currentTimeMillis()
                ));
                result.addAll(items);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectRef ownerOf(T item) {
        if (item instanceof ColumnMetadata column) {
            return column.owner();
        }
        if (item instanceof PrimaryKeyMetadata pk) {
            return pk.owner();
        }
        if (item instanceof ForeignKeyMetadata fk) {
            return fk.owner();
        }
        return null;
    }

    private static boolean isEmpty(Object value) {
        return value instanceof Collection<?> collection && collection.isEmpty();
    }

    private void invalidatePrefix(String catalog, String schema) {
        invalidatePrefix(catalog, schema, null);
    }

    private void invalidatePrefix(String catalog, String schema, String object) {
        cache.keySet().removeIf(key ->
                (object == null || key.object() == null || key.object().equals(object))
                        && Objects.equals(key.catalog(), catalog)
                        && Objects.equals(key.schema(), schema));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object value) {
        return value instanceof List<?> list ? (List<T>) list : List.of();
    }

    private enum Category {
        CATALOGS, SCHEMAS, RELATIONS, COLUMNS, INDEXES, STATISTICS,
        PRIMARY_KEYS, FOREIGN_KEYS, DEFINITIONS
    }

    private enum State {
        LOADED, LOADED_EMPTY, UNSUPPORTED
    }

    private record CacheKey(String catalog, String schema, String object, Category category) {
    }

    private record CacheEntry(State state, Object value, long loadedAt) {
    }

    @FunctionalInterface
    private interface Loader<T> {
        T load() throws SQLException;
    }

    @FunctionalInterface
    private interface ObjectLoader<T> {
        List<T> load(List<ObjectRef> relations) throws SQLException;
    }
}
