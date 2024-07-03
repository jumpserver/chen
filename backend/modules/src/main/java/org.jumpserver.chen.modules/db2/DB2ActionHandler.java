package org.jumpserver.chen.modules.db2;

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
public class DB2ActionHandler extends BaseActionHandler {

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
                ,
                Action.builder()
                        .label(MessageUtils.get("action.show_properties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
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
                        .build(),
                Action.builder()
                        .label(MessageUtils.get("action.show_properties"))
                        .key("show_properties")
                        .icon("fa fa-align-justify")
                        .build()
        );
    }

    private static final String SQL_SELECT_SCHEMA_DETAIL = "select * from syscat.schemata where schemaname = '?'";

    public EventEmitter onSchemaProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("schema", SQL_SELECT_SCHEMA_DETAIL, node);
    }

    private static final String SQL_SELECT_TABLE_DETAIL = "select TABNAME, TBSPACE, TABSCHEMA, TYPE, STATUS, COLCOUNT, ACTIVE_BLOCKS, AVGROWSIZE, OWNER, CREATE_TIME from syscat.TABLES WHERE TBSPACE is not null AND TABNAME = '?'";

    public EventEmitter onTableProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("table", SQL_SELECT_TABLE_DETAIL, node);
    }


    @Override
    public EventEmitter handleForm(FormData formData) throws SQLException {
        return null;
    }
}
