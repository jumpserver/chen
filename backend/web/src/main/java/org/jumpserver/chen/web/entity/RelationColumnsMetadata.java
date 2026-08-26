package org.jumpserver.chen.web.entity;

import java.util.List;

public record RelationColumnsMetadata(QualifiedRelation relation, List<SqlColumnMetadata> columns) {
}
