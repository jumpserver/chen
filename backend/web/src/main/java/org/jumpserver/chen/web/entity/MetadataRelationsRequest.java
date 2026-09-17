package org.jumpserver.chen.web.entity;

import lombok.Data;

@Data
public class MetadataRelationsRequest {
    private String nodeKey;
    private String context;
    private String prefix;
    private Integer limit;
}
