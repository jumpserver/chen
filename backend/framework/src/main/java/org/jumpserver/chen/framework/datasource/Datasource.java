package org.jumpserver.chen.framework.datasource;


import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.entity.DatasourceInfo;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;


import java.sql.SQLException;
import java.util.List;

public interface Datasource {
    String getName();
    DbType getDruidDbType();

    void ping();

    void init();

    DatasourceInfo getInfo() ;

    List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException;

    List<Action> getActions(TreeNode node);

    EventEmitter doAction(TreeNode node, String action) throws SQLException;

    EventEmitter handleForm(FormData formData) throws SQLException;

    DBConnectInfo getConnectInfo();

    ConnectionManager getConnectionManager();
    ResourceBrowser getResourceBrowser();

    void close();
}
