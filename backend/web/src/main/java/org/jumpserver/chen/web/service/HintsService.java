package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HintsService {
    private static final Set<String> QUERY_NODE_TYPES = Set.of("datasource", "database", "schema", "table");
    private static final Set<RelationKind> HINT_KINDS = Set.of(RelationKind.TABLE, RelationKind.VIEW);

    public Map<String, List<String>> getHints(String nodeKey, String context) {
        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return Map.of();
        }

        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveNode(browser, nodeKey);
        try {
            var scope = browser.resolveScope(node, context);
            var catalog = datasource.getMetadataCatalog();
            var relations = catalog.listRelations(scope, HINT_KINDS);
            var columnsByName = new LinkedHashMap<String, List<String>>();
            for (var column : catalog.listColumns(relations.stream().map(RelationMetadata::ref).toList())) {
                columnsByName.computeIfAbsent(column.owner().name(), ignored -> new ArrayList<>()).add(column.name());
            }

            var suggestions = new LinkedHashMap<String, List<String>>();
            suggestions.put(StringUtils.defaultString(scope.schema()),
                    relations.stream().map(relation -> relation.ref().name()).toList());
            for (var relation : relations) {
                suggestions.put(relation.ref().name(), columnsByName.getOrDefault(relation.ref().name(), List.of()));
            }
            return suggestions;
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load SQL hints", e);
        }
    }

    private ResourceNodeSnapshot resolveNode(ResourceBrowser browser, String nodeKey) {
        if (StringUtils.isBlank(nodeKey)) {
            throw new ChenException("Invalid metadata context");
        }
        var node = browser.getIndexedNode(nodeKey);
        if (node == null || !QUERY_NODE_TYPES.contains(node.type())) {
            throw new ChenException("Invalid metadata context");
        }
        return node;
    }
}
