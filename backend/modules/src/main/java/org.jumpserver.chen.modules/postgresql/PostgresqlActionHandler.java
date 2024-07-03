package org.jumpserver.chen.modules.postgresql;

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
public class PostgresqlActionHandler extends BaseActionHandler {

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
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.show_properties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    public List<Action> getDatabaseActions(TreeNode node) {
        return List.of(
                Action.builder()
                        .label(MessageUtils.get("action.new_query"))
                        .key("new_query")
                        .icon("el-icon-search")
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.show_properties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    private static final String SQL_SELECT_DATABASE_DETAIL = "SELECT datname,pg_database_size(datname) as size,pg_database_size(datname) - pg_database_size(datname) as size_free,pg_database_size(datname) / pg_database_size(datname) as size_percent FROM pg_database WHERE datname = '?'";

    public EventEmitter onDatabaseProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("database", SQL_SELECT_DATABASE_DETAIL, node);
    }


    private static final String SQL_SELECT_TABLE_DETAIL = "SELECT table_name,table_schema,table_type FROM information_schema.tables WHERE  table_name = '?";

    public EventEmitter onTableProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("table", SQL_SELECT_TABLE_DETAIL, node);
    }


    @Override
    public EventEmitter handleForm(FormData formData) throws SQLException {
        return null;
    }
}
