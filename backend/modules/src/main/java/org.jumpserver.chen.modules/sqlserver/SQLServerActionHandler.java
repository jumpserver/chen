package org.jumpserver.chen.modules.sqlserver;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.base.BaseActionHandler;
import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.i18n.MessageUtils;

import java.sql.SQLException;
import java.util.List;

@Slf4j
public class SQLServerActionHandler extends BaseActionHandler {

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

    public List<Action> getDatabaseActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("NewQuery"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("ShowProperties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    private static final String SQL_SELECT_DATABASE_DETAIL = "SELECT name,collation_name  FROM SYS.DATABASES WHERE name = '?'";
    public EventEmitter onDatabaseProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("database", SQL_SELECT_DATABASE_DETAIL, node);
    }


    @Override
    public EventEmitter handleForm(FormData formData) throws SQLException {
        return null;
    }
}
