package org.jumpserver.chen.modules.oracle;

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
public class OracleActionHandler extends BaseActionHandler {

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

    private static final String SQL_SELECT_TABLE_DETAIL = "SELECT TABLE_NAME,TABLESPACE_NAME,STATUS,NUM_ROWS,BLOCKS,AVG_ROW_LEN,SAMPLE_SIZE,OWNER FROM ALL_TABLES WHERE TABLESPACE_NAME is not null AND TABLE_NAME = '?'";

    public EventEmitter onTableProperties(TreeNode node) throws SQLException {
        return this.onShowObjectProperties("table", SQL_SELECT_TABLE_DETAIL, node);
    }

    @Override
    public EventEmitter handleForm(FormData formData) throws SQLException {
        return null;
    }
}
