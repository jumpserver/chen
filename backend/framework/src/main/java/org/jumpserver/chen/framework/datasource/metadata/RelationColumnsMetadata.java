package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record RelationColumnsMetadata(
        QualifiedRelation relation,
        List<SqlColumnMetadata> columns
) {
}
