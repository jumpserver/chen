package org.jumpserver.chen.framework.datasource.base;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.ActionHandler;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailDialog;
import org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailItem;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.metadata.ScopeKind;
import org.jumpserver.chen.framework.datasource.metadata.ScopeRef;
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
                        .label(MessageUtils.get("Refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("NewQuery"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build()
                ,
                Action.builder()
                        .label(MessageUtils.get("ShowProperties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    @Override
    public List<Action> getSchemaActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("Refresh"))
                        .key("refresh_node")
                        .divided(true)
                        .icon("el-icon-refresh")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("NewQuery"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build()
        );
    }

    @Override
    public List<Action> getTableActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("NewQuery"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("ViewData"))
                        .key("view_data")
                        .icon("el-icon-view")
                        .build()
        );
    }

    @Override
    public List<Action> getViewActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("ViewData"))
                        .key("view_data")
                        .icon("el-icon-view")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("Refresh"))
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
                        .label(MessageUtils.get("Refresh"))
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

    public EventEmitter onTableProperties(TreeNode node) throws SQLException {
        return this.showRelationProperties(node);
    }

    public EventEmitter onViewProperties(TreeNode node) throws SQLException {
        return this.showRelationProperties(node);
    }

    private EventEmitter showRelationProperties(TreeNode node) throws SQLException {
        var database = TreeUtils.getValue(node.getKey(), "database");
        var schema = TreeUtils.getValue(node.getKey(), "schema");
        var table = TreeUtils.getValue(node.getKey(), node.getType());
        var kind = "view".equals(node.getType()) ? RelationKind.VIEW : RelationKind.TABLE;
        var ref = new ObjectRef(database.isEmpty() ? null : database, schema, table, kind);
        var properties = this.getDatasource().getMetadataCatalog().objectProperties(ref);

        var detailDialog = new DetailDialog(node.getKey(), node.getType() + MessageUtils.get("Properties"));
        detailDialog.setWidth("50%");
        for (var item : properties.items()) {
            detailDialog.addItem(DetailItem.builder()
                    .name(item.name())
                    .label(item.name())
                    .type("text")
                    .value(item.value() == null ? "" : item.value())
                    .build());
        }
        return EventEmitter.of("new_dialog", detailDialog);
    }

    public EventEmitter onSchemaProperties(TreeNode node) throws SQLException {
        return this.showScopeProperties(node, ScopeKind.SCHEMA);
    }

    public EventEmitter onDatabaseProperties(TreeNode node) throws SQLException {
        return this.showScopeProperties(node, ScopeKind.CATALOG);
    }

    private EventEmitter showScopeProperties(TreeNode node, ScopeKind kind) throws SQLException {
        var datasource = this.getDatasource();
        var browser = datasource.getResourceBrowser();
        var snapshot = browser.getIndexedNode(node.getKey());
        var scope = browser.resolveScope(snapshot, null);
        if (kind == ScopeKind.CATALOG) {
            scope = new RelationScope(scope.catalog(), null);
        }
        var properties = datasource.getMetadataCatalog().scopeProperties(new ScopeRef(scope, kind));
        var detailDialog = new DetailDialog(node.getKey(), node.getType() + MessageUtils.get("Properties"));
        detailDialog.setWidth("50%");
        for (var item : properties.items()) {
            detailDialog.addItem(DetailItem.builder()
                    .name(item.name())
                    .label(item.name())
                    .type("text")
                    .value(item.value() == null ? "" : item.value())
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
        var dialog = new DetailDialog(node.getKey(), MessageUtils.get("DatasourceProperties"));
        dialog.setWidth("50%");
        var info = SessionManager.getCurrentSession().getDatasource().getInfo();
        dialog.addItem(DetailItem.builder()
                        .name("name")
                        .label(MessageUtils.get("Name"))
                        .value(info.getName())
                        .build())
                .addItem(DetailItem.builder()
                        .name("type")
                        .label(MessageUtils.get("Type"))
                        .value(info.getDbType())
                        .build())
                .addItem(DetailItem.builder()
                        .name("version")
                        .label(MessageUtils.get("Version"))
                        .value(info.getVersion())
                        .build())
                .addItem(DetailItem.builder()
                        .name("dbUser")
                        .label(MessageUtils.get("User"))
                        .value(info.getDbUser())
                        .build())
                .addItem(DetailItem.builder()
                        .name("jdbcUrl")
                        .label(MessageUtils.get("JDBCURL"))
                        .value(info.getJdbcUrl())
                        .build())
                .addItem(DetailItem.builder()
                        .name("driverClass")
                        .label(MessageUtils.get("DriverClass"))
                        .value(info.getDriverClassName())
                        .build())
                .addItem(DetailItem.builder()
                        .name("driverVersion")
                        .label(MessageUtils.get("DriverVersion"))
                        .value(info.getDriverVersion())
                        .build());
        return EventEmitter.of("new_dialog", dialog);
    }

    public EventEmitter onRefreshNode(TreeNode node) throws SQLException {
        var datasource = this.getDatasource();
        var resourceBrowser = datasource.getResourceBrowser();

        var n = TreeUtils.getNode(resourceBrowser.getTree(), node.getKey());
        if (n != null) {
            this.invalidateMetadata(n);
            resourceBrowser.getChildren(n, false);
        }
        return EventEmitter.of("refresh_node", node.getKey());
    }

    private void invalidateMetadata(TreeNode node) throws SQLException {
        var datasource = this.getDatasource();
        var browser = datasource.getResourceBrowser();
        var catalog = datasource.getMetadataCatalog();
        var snapshot = browser.getIndexedNode(node.getKey());
        switch (node.getType()) {
            case "datasource" -> catalog.invalidateAll();
            case "database" -> catalog.invalidateCatalog(snapshot.database());
            case "schema", "folder" -> catalog.invalidate(browser.resolveScope(snapshot, null));
            case "table", "view" -> {
                var scope = browser.resolveScope(snapshot, null);
                var kind = "view".equals(node.getType()) ? RelationKind.VIEW : RelationKind.TABLE;
                catalog.invalidate(new ObjectRef(scope.catalog(), scope.schema(), snapshot.table(), kind));
            }
            default -> {
            }
        }
    }

    public EventEmitter onNewQuery(TreeNode node) {
        return EventEmitter.of("new_query", node.getKey());
    }

    public EventEmitter onViewData(TreeNode node) {
        return EventEmitter.of("view_data", node.getKey());
    }
}
