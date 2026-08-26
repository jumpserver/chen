package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record ScopeProperties(ScopeRef ref, List<PropertyItem> items) {
}
