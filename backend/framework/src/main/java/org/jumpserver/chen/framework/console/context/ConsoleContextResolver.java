package org.jumpserver.chen.framework.console.context;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;

import java.util.Set;

public class ConsoleContextResolver {
    private static final Set<String> QUERY_NODE_TYPES = Set.of("datasource", "database", "schema", "table");
    private static final Set<String> DATA_VIEW_NODE_TYPES = Set.of("table", "view");

    private final ResourceBrowser resourceBrowser;

    public ConsoleContextResolver(ResourceBrowser resourceBrowser) {
        this.resourceBrowser = resourceBrowser;
    }

    public ConsoleContext resolve(String submittedNodeKey, String consoleType) {
        if (StringUtils.isBlank(submittedNodeKey) || StringUtils.isBlank(consoleType)) {
            throw new ConsoleContextResolutionException("Invalid console context");
        }

        var node = this.resourceBrowser.getIndexedNode(submittedNodeKey);
        if (node == null || !isAllowedNodeType(consoleType, node.type())) {
            throw new ConsoleContextResolutionException("Invalid console context");
        }

        return new ConsoleContext(
                node.key(),
                node.type(),
                node.database(),
                node.schema(),
                node.table()
        );
    }

    private static boolean isAllowedNodeType(String consoleType, String nodeType) {
        return switch (consoleType) {
            case Connect.CONSOLE_TYPE_QUERY, Connect.CONSOLE_TYPE_CONSOLE -> QUERY_NODE_TYPES.contains(nodeType);
            case Connect.CONSOLE_TYPE_DATA_VIEW -> DATA_VIEW_NODE_TYPES.contains(nodeType);
            default -> false;
        };
    }
}
