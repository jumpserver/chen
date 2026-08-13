package org.jumpserver.chen.web.service;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.ResourceNodeSnapshot;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadataPage;
import org.jumpserver.chen.framework.datasource.metadata.SqlMetadataCatalog;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.MetadataColumnsRequest;
import org.jumpserver.chen.web.entity.MetadataColumnsResponse;
import org.jumpserver.chen.web.entity.MetadataRelationsRequest;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

@Service
public class SqlMetadataService {
    private static final Set<String> QUERY_NODE_TYPES = Set.of("datasource", "database", "schema", "table");

    public RelationMetadataPage listRelations(MetadataRelationsRequest request) {
        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return new RelationMetadataPage(List.of(), false);
        }

        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveNode(browser, request == null ? null : request.getNodeKey());
        var catalog = new SqlMetadataCatalog(browser, datasource.getConnectionManager());
        try {
            return catalog.listRelations(node, request.getContext(), request.getPrefix(), request.getLimit());
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load SQL relation metadata", e);
        }
    }

    public MetadataColumnsResponse listColumns(MetadataColumnsRequest request) {
        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return new MetadataColumnsResponse(List.of());
        }

        var datasource = session.getDatasource();
        var browser = datasource.getResourceBrowser();
        var node = this.resolveNode(browser, request == null ? null : request.getNodeKey());
        var catalog = new SqlMetadataCatalog(browser, datasource.getConnectionManager());
        try {
            return new MetadataColumnsResponse(catalog.listColumns(node, request.getContext(), request.getRelations()));
        } catch (SQLException | IllegalArgumentException e) {
            throw new ChenException("Failed to load SQL column metadata", e);
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
