package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.modules.postgresql.entity.Database;

import java.sql.SQLException;
import java.util.List;

public class SQLServerResourceBrowser extends BaseResourceBrowser {
    public SQLServerResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    public List<TreeNode> getDatasourceChildNodes(TreeNode parent) throws SQLException {
        return this.getDatabases().stream().map(database -> database.toResourceNode(parent)).toList();
    }

    public List<TreeNode> getDatabaseChildNodes(TreeNode node) throws SQLException {
        var dbName = TreeUtils.getValue(node.getKey(), "database");
        return this.metadataCatalog().listSchemas(dbName).stream()
                .map(schema -> {
                    var entity = new Schema();
                    entity.setName(schema.name());
                    return entity.toResourceNode(node);
                })
                .toList();
    }

    public List<Database> getDatabases() throws SQLException {
        return this.metadataCatalog().listCatalogs().stream()
                .map(catalog -> {
                    var database = new Database();
                    database.setName(catalog.name());
                    return database;
                })
                .toList();
    }
}
