package org.jumpserver.chen.web.entity;

import java.util.Set;

public record TableMetadataRequest(String nodeKey, Set<String> sections, boolean force) {
    public TableMetadataRequest(String nodeKey) {
        this(nodeKey, null, false);
    }

    public TableMetadataRequest(String nodeKey, Set<String> sections) {
        this(nodeKey, sections, false);
    }
}
