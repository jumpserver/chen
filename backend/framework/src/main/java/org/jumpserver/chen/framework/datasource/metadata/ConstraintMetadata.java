package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

/** Canonical table constraint metadata for PK, FK, UNIQUE and CHECK constraints. */
public record ConstraintMetadata(
        ObjectRef owner,
        String name,
        ConstraintType type,
        List<String> columns,
        ObjectRef referenced,
        List<String> referencedColumns,
        String definition
) {
}
