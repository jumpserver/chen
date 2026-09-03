package org.jumpserver.chen.web.entity;

import java.util.Set;

public record TableMetadataRequest(String nodeKey, Set<String> sections) {
    public TableMetadataRequest(String nodeKey) {
        this(nodeKey, null);
    }
}
