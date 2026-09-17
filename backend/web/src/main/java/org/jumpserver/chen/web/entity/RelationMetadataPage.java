package org.jumpserver.chen.web.entity;

import java.util.List;

public record RelationMetadataPage(List<QualifiedRelation> items, boolean truncated) {
}
