package org.jumpserver.chen.web.entity;

import org.jumpserver.chen.framework.datasource.metadata.RelationColumnsMetadata;

import java.util.List;

public record MetadataColumnsResponse(List<RelationColumnsMetadata> items) {
}
