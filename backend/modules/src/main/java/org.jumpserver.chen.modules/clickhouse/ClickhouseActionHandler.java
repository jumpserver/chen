package org.jumpserver.chen.modules.clickhouse;

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
public class ClickhouseActionHandler extends BaseActionHandler {

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
                ,
                Action.builder()
                        .label(MessageUtils.get("ShowProperties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
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
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("ShowProperties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    private static final String SQL_SELECT_SCHEMA_DETAIL = "select * from information_schema.SCHEMATA where SCHEMA_NAME = '?'";

    public EventEmitter onSchemaProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("schema", SQL_SELECT_SCHEMA_DETAIL, node);
    }

    private static final String SQL_SELECT_TABLE_DETAIL = "select TABLE_NAME,TABLE_SCHEMA,TABLE_TYPE,ENGINE,AVG_ROW_LENGTH,DATA_LENGTH,MAX_DATA_LENGTH,CREATE_TIME,TABLE_COLLATION from information_schema.TABLES WHERE TABLE_NAME = '?'";

    public EventEmitter onTableProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("table", SQL_SELECT_TABLE_DETAIL, node);
    }


    @Override
    public EventEmitter handleForm(FormData formData) throws SQLException {
        return null;
    }
}
