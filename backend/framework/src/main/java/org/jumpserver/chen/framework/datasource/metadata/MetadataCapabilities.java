package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Per-dialect capability flags. Coarse category flags drive whether the catalog
 * may call a provider method; fine field-level flags drive which relation/
 * statistics columns a consumer (e.g. schema overview) may render.
 */
public record MetadataCapabilities(
        boolean catalogs,
        boolean schemas,
        boolean relations,
        boolean columns,
        boolean indexes,
        boolean primaryKeys,
        boolean foreignKeys,
        boolean statistics,
        boolean definitions,
        boolean tableRows,
        boolean tableSize,
        boolean tableEngine,
        boolean tableCharacterSet,
        boolean tableCollation,
        boolean tableComment,
        boolean viewComment
) {
}
