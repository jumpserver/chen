package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record RelationMetadataPage(
        List<QualifiedRelation> items,
        boolean truncated
) {
}
