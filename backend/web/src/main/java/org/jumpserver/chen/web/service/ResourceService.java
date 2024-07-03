package org.jumpserver.chen.web.service;

import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;


@Service
public class ResourceService {

    public List<TreeNode> getChildren(TreeNode node, boolean force) {
        try {
            var ds = SessionManager.getCurrentSession().getDatasource();
            return ds.getChildren(node, !force);
        } catch (SQLException e) {
            throw new ChenException(String.format("获取 %s子节点失败", node.getLabel()), e);
        }
    }

    public List<Action> getActions(TreeNode node) {
        var ds = SessionManager.getCurrentSession().getDatasource();
        return ds.getActions(node);
    }

    public EventEmitter doAction(TreeNode node, String action) {
        try {
            var ds = SessionManager.getCurrentSession().getDatasource();
            return ds.doAction(node, action);
        } catch (Exception e) {
            throw new ChenException(String.format("执行节点动作 %s 失败", node.getLabel()), e);
        }
    }

    public EventEmitter submitResourceForm(FormData form) throws SQLException {
        var ds = SessionManager.getCurrentSession().getDatasource();
        return ds.handleForm(form);
    }
}
