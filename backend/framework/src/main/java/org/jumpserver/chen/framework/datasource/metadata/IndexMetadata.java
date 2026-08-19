package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record IndexMetadata(
        String name,
        ObjectRef owner,
        boolean unique,
        String method,
        List<IndexPart> parts,
        String definition
) {
}
