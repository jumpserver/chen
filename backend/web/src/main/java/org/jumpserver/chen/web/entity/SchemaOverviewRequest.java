package org.jumpserver.chen.web.entity;

import java.util.Set;

public record SchemaOverviewRequest(String nodeKey, Set<String> sections, boolean force) {
    public SchemaOverviewRequest(String nodeKey) {
        this(nodeKey, null, false);
    }

    public SchemaOverviewRequest(String nodeKey, Set<String> sections) {
        this(nodeKey, sections, false);
    }
}
