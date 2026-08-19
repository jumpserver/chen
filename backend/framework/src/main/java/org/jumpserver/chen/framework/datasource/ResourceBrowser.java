package org.jumpserver.chen.framework.datasource;

import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;

import java.sql.SQLException;
import java.util.List;

public interface ResourceBrowser {
    void buildTree() throws SQLException;

    TreeNode getTree() throws SQLException;

    List<TreeNode> getChildren(TreeNode node) throws SQLException;

    List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException;

    List<Schema> getSchemas() throws SQLException;

    List<Table> getTables(String schema) throws SQLException;

    List<View> getViews(String schema) throws SQLException;

    List<Field> getFields(String schema, String table) throws SQLException;

    ResourceNodeSnapshot getIndexedNode(String key);

    SQLActuator getSQLActuator();
    SQLHintsHandler getSQLHintsHandler();
}
