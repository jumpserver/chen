package org.jumpserver.chen.framework.datasource;

import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;

import java.sql.SQLException;
import java.util.List;

public interface ActionHandler {
    List<Action> getActions(TreeNode node);

    List<Action> getDatasourceActions(TreeNode node);

    List<Action> getSchemaActions(TreeNode node);

    List<Action> getTableActions(TreeNode node);

    List<Action> getViewActions(TreeNode node);

    List<Action> getFieldActions(TreeNode node);

    List<Action> getFolderActions(TreeNode node);

    EventEmitter doAction(TreeNode node, String action) throws SQLException;

    EventEmitter handleForm(FormData formData) throws SQLException;
}
