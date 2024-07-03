package org.jumpserver.chen.modules.dameng;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class DMResourceBrowser extends BaseResourceBrowser {
    public DMResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new DMSQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "SELECT NAME FROM SYSOBJECTS t WHERE t.TYPE$ ='SCH'";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "SELECT TABLE_NAME AS NAME FROM ALL_TABLES  WHERE OWNER='?'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "SELECT VIEW_NAME AS NAME FROM ALL_VIEWS  WHERE OWNER='?'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT COLUMN_NAME AS `NAME`,DATA_TYPE AS `TYPE`,DATA_LENGTH AS `LENGTH`,DATA_DEFAULT AS `DEFAULT` FROM ALL_TAB_COLUMNS WHERE OWNER='?' AND TABLE_NAME='?'";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }



}
