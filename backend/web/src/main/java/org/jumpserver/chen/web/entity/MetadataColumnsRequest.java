package org.jumpserver.chen.web.entity;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.metadata.QualifiedRelation;

import java.util.List;

@Data
public class MetadataColumnsRequest {
    private String nodeKey;
    private String context;
    private List<QualifiedRelation> relations;
}
