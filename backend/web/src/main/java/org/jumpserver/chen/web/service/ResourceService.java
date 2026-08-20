package org.jumpserver.chen.web.service;

import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;


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
            var resolvedNode = this.resolveActionNode(ds, node, action);
            return ds.doAction(resolvedNode, action);
        } catch (Exception e) {
            if (e instanceof ChenException) {
                throw (ChenException) e;
            }
            throw new ChenException(String.format("执行节点动作 %s 失败", node.getLabel()), e);
        }
    }

    private TreeNode resolveActionNode(org.jumpserver.chen.framework.datasource.Datasource datasource,
                                       TreeNode requestedNode, String action) throws SQLException {
        if (requestedNode == null || requestedNode.getKey() == null || requestedNode.getKey().isBlank()
                || action == null || action.isBlank()) {
            throw new ChenException("Invalid resource action");
        }

        var root = datasource.getResourceBrowser().getTree();
        var resolvedNode = root == null ? null : TreeUtils.getNode(root, requestedNode.getKey());
        if (resolvedNode == null || !Objects.equals(resolvedNode.getType(), requestedNode.getType())) {
            throw new ChenException("Invalid resource node");
        }

        var exposed = datasource.getActions(resolvedNode).stream()
                .anyMatch(candidate -> Objects.equals(candidate.getKey(), action));
        if (!exposed) {
            throw new ChenException("Invalid resource action");
        }
        return resolvedNode;
    }

    public EventEmitter submitResourceForm(FormData form) throws SQLException {
        var ds = SessionManager.getCurrentSession().getDatasource();
        return ds.handleForm(form);
    }
}
