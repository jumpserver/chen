package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record ForeignKeyMetadata(
        ObjectRef owner,
        String name,
        List<String> columns,
        ObjectRef referenced,
        List<String> referencedColumns
) {
}
