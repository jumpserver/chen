package org.jumpserver.chen.framework.datasource.base;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.ActionHandler;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailDialog;
import org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailItem;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.LangUtils;
import org.jumpserver.chen.framework.utils.TreeUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.List;

@Slf4j
public abstract class BaseActionHandler implements ActionHandler {


    public Datasource getDatasource() {
        return SessionManager.getCurrentSession().getDatasource();
    }

    @Override
    public List<Action> getActions(TreeNode node) {
        if (node == null) {
            return List.of();
        }
        var methodName = LangUtils.toHumpStr("get_" + node.getType() + "_actions");
        try {
            return (List<Action>) this.runTargetMethod(methodName, node);
        } catch (ReflectiveOperationException e) {
            log.error("getActions error {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public List<Action> getDatasourceActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.new_query"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build()
                ,
                Action.builder()
                        .label(MessageUtils.get("action.show_properties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    @Override
    public List<Action> getSchemaActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.new_query"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build()
        );
    }

    @Override
    public List<Action> getTableActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.new_query"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.view_data"))
                        .key("view_data")
                        .icon("el-icon-view")
                        .build()
        );
    }

    @Override
    public List<Action> getViewActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build()
        );
    }

    @Override
    public List<Action> getFieldActions(TreeNode node) {
        return List.of();
    }

    @Override
    public List<Action> getFolderActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build()
        );
    }

    @Override
    public EventEmitter doAction(TreeNode node, String action) {
        var methodName = LangUtils.toHumpStr("on_" + action);
        try {
            return (EventEmitter) this.runTargetMethod(methodName, node);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public EventEmitter onShow(TreeNode node) {
        switch (node.getType()) {
            case "table", "view": {
                return EventEmitter.of("view_data", node.getKey());
            }
        }
        return EventEmitter.of("blank", null);
    }


    public EventEmitter onShowProperties(TreeNode node) {
        var methodName = LangUtils.toHumpStr(String.format("on_%s_properties", node.getType()));
        try {
            return (EventEmitter) this.runTargetMethod(methodName, node);

        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public EventEmitter onShowObjectProperties(String type, String sql, TreeNode node) throws SQLException {
        var sqlActuator = this.getDatasource().getConnectionManager().getSqlActuator();
        var objName = TreeUtils.getValue(node.getKey(), type);
        var result = sqlActuator.execute(SQL.of(sql, objName));

        var detailDialog = new DetailDialog(node.getKey(), type + MessageUtils.get("title.properties"));
        detailDialog.setWidth("50%");
        for (int i = 0; i < result.getFields().size(); i++) {
            var column = result.getFields().get(i);
            detailDialog.addItem(DetailItem.builder()
                    .name(column.getName())
                    .label(column.getName())
                    .type("text")
                    .value(result.getData().get(0).get(i) == null ? "" : result.getData().get(0).get(i).toString())
                    .build());
        }
        return EventEmitter.of("new_dialog", detailDialog);
    }

    private Object runTargetMethod(String methodName, TreeNode node) throws ReflectiveOperationException {
        return this.getClass()
                .getMethod(methodName, TreeNode.class)
                .invoke(this, node);
    }


    public EventEmitter onDatasourceProperties(TreeNode node) {
        var dialog = new DetailDialog(node.getKey(), MessageUtils.get("title.datasource_properties"));
        dialog.setWidth("50%");
        var info = SessionManager.getCurrentSession().getDatasource().getInfo();
        dialog.addItem(DetailItem.builder()
                        .name("name")
                        .label(MessageUtils.get("action.label.name"))
                        .value(info.getName())
                        .build())
                .addItem(DetailItem.builder()
                        .name("type")
                        .label(MessageUtils.get("action.label.type"))
                        .value(info.getDbType())
                        .build())
                .addItem(DetailItem.builder()
                        .name("version")
                        .label(MessageUtils.get("action.label.version"))
                        .value(info.getVersion())
                        .build())
                .addItem(DetailItem.builder()
                        .name("dbUser")
                        .label(MessageUtils.get("action.label.user"))
                        .value(info.getDbUser())
                        .build())
                .addItem(DetailItem.builder()
                        .name("jdbcUrl")
                        .label(MessageUtils.get("action.label.jdbc_url"))
                        .value(info.getJdbcUrl())
                        .build())
                .addItem(DetailItem.builder()
                        .name("driverClass")
                        .label(MessageUtils.get("action.label.driver_class"))
                        .value(info.getDriverClassName())
                        .build())
                .addItem(DetailItem.builder()
                        .name("driverVersion")
                        .label(MessageUtils.get("action.label.driver_version"))
                        .value(info.getDriverVersion())
                        .build());
        return EventEmitter.of("new_dialog", dialog);
    }

    public EventEmitter onRefreshNode(TreeNode node) throws SQLException {
        var resourceBrowser = this.getDatasource().getResourceBrowser();

        var n = TreeUtils.getNode(resourceBrowser.getTree(), node.getKey());
        if (n != null) {
            var children = resourceBrowser.getChildren(n, false);
            n.setChildren(children);
        }
        return EventEmitter.of("refresh_node", node.getKey());
    }

    public EventEmitter onNewQuery(TreeNode node) {
        return EventEmitter.of("new_query", node.getKey());
    }

    public EventEmitter onViewData(TreeNode node) {
        return EventEmitter.of("view_data", node.getKey());
    }
}
