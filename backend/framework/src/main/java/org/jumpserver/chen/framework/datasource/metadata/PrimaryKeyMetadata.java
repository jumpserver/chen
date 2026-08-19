package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

public record PrimaryKeyMetadata(ObjectRef owner, String name, List<String> columns) {
}
