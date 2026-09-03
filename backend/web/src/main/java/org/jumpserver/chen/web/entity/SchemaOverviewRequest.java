package org.jumpserver.chen.web.entity;

import java.util.Set;

public record SchemaOverviewRequest(String nodeKey, Set<String> sections) {
    public SchemaOverviewRequest(String nodeKey) {
        this(nodeKey, null);
    }
}
