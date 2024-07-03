package org.jumpserver.chen.framework.datasource;

import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;

import java.sql.SQLException;
import java.util.List;

public interface ResourceBrowser {
    void buildTree() throws SQLException;

    TreeNode getTree() throws SQLException;

    List<TreeNode> getChildren(TreeNode node) throws SQLException;

    List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException;

    List<Schema> getSchemas(SQL sql) throws SQLException;

    List<Table> getTables(SQL sql) throws SQLException;

    List<View> getViews(SQL sql) throws SQLException;

    List<Field> getFields(SQL sql) throws SQLException;

    SQLActuator getSQLActuator();
    SQLHintsHandler getSQLHintsHandler();
}
