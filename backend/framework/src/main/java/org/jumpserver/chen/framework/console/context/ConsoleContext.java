package org.jumpserver.chen.framework.console.context;

public record ConsoleContext(
        String nodeKey,
        String nodeType,
        String database,
        String schema,
        String table
) {
}
