package org.jumpserver.chen.framework.datasource.base;

import org.jumpserver.chen.framework.datasource.ActionHandler;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.entity.DatasourceInfo;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.session.SessionManager;

import java.sql.SQLException;
import java.util.List;

public abstract class BaseDatasource implements Datasource {

    protected ConnectionManager connectionManager;

    protected ResourceBrowser resourceBrowser;
    protected ActionHandler actionHandler;
    protected DatasourceInfo datasourceInfo;

    public void ping() {
        try {
            this.connectionManager.ping();
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage());
        }
    }


    public DatasourceInfo getInfo() {
        if (this.datasourceInfo != null) {
            return this.datasourceInfo;
        }
        try {
            this.datasourceInfo = new DatasourceInfo();
            this.datasourceInfo.setName(SessionManager.getCurrentSession().getDatasourceName());
            this.datasourceInfo.setDbType(this.connectionManager.getConnectInfo().getDbType());
            this.datasourceInfo.setDbUser(this.connectionManager.getConnectInfo().getUser());
            this.datasourceInfo.setJdbcUrl(this.connectionManager.getDisplayJDBCUrl());
            this.datasourceInfo.setVersion(this.connectionManager.getVersion());

            this.datasourceInfo.setDriverClassName(this.connectionManager.getDriverClassName());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return this.datasourceInfo;
    }

    public void init() {
        try {
            this.getInfo();
            this.resourceBrowser.buildTree();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException {
        return this.resourceBrowser.getChildren(node, fromCache);
    }

    public List<Action> getActions(TreeNode node) {
        return this.actionHandler.getActions(node);
    }

    public EventEmitter doAction(TreeNode node, String action) throws SQLException {
        return this.actionHandler.doAction(node, action);
    }

    public EventEmitter handleForm(FormData formData) throws SQLException {
        return this.actionHandler.handleForm(formData);
    }

    public ConnectionManager getConnectionManager() {
        return this.connectionManager;
    }

    public ResourceBrowser getResourceBrowser() {
        return this.resourceBrowser;
    }

    public DBConnectInfo getConnectInfo() {
        return this.connectionManager.getConnectInfo();
    }

    public void close() {
        this.connectionManager.close();
    }

}
