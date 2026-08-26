package org.jumpserver.chen.framework.datasource.metadata;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Canonical metadata source for one SQL dialect. Providers are stateless with
 * respect to cache; they hold a {@link org.jumpserver.chen.framework.datasource.ConnectionManager}
 * reference and issue parameterized metadata queries.
 *
 * <p>The catalog never calls a category whose {@link #capabilities()} flag is
 * {@code false}. Unsupported categories therefore either return an empty/natural
 * default (containers, schema DDL, vendor properties) or throw
 * {@link UnsupportedOperationException} from the base implementation.</p>
 */
public interface DatabaseMetadataProvider {

    MetadataCapabilities capabilities();

    List<CatalogMetadata> listCatalogs() throws SQLException;

    List<SchemaMetadata> listSchemas(String catalog) throws SQLException;

    /**
     * Full relation list for a scope. Cacheable; {@code kinds} restricts which
     * relation kinds are returned.
     */
    List<RelationMetadata> listRelations(RelationScope scope, Set<RelationKind> kinds) throws SQLException;

    /**
     * Completion-oriented search. Prefix and limit are applied by the caller
     * (base default filters in memory); a dialect may override to push the
     * predicate down. Not cached by the catalog.
     */
    List<RelationMetadata> searchRelations(RelationScope scope, Set<RelationKind> kinds, String prefix, int limit)
            throws SQLException;

    /**
     * Batched column load. Every returned {@link ColumnMetadata} carries its
     * owning {@link ObjectRef} so the caller can reassociate batch results.
     */
    List<ColumnMetadata> listColumns(List<ObjectRef> relations) throws SQLException;

    List<IndexMetadata> listIndexes(RelationScope scope) throws SQLException;

    List<ObjectStatistics> listStatistics(RelationScope scope) throws SQLException;

    List<PrimaryKeyMetadata> listPrimaryKeys(List<ObjectRef> relations) throws SQLException;

    List<ForeignKeyMetadata> listForeignKeys(List<ObjectRef> relations) throws SQLException;

    /**
     * Schema/database DDL, or {@code null} when the dialect does not provide one.
     */
    String getSchemaDefinition(RelationScope scope) throws SQLException;

    ObjectProperties objectProperties(ObjectRef ref) throws SQLException;

    ScopeProperties scopeProperties(ScopeRef ref) throws SQLException;
}
