package org.jumpserver.chen.framework.datasource.metadata;

public record SqlColumnMetadata(
        String name,
        String dataType,
        boolean nullable
) {
}
