package org.jumpserver.chen.modules.clickhouse;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class ClickhouseResourceBrowser extends BaseResourceBrowser {
    public ClickhouseResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new ClickhouseSQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "select schema_name as name from information_schema.schemata";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "select table_name  from information_schema.tables where table_schema = '?'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "select table_name  from information_schema.views where table_schema = '?'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT COLUMN_NAME AS NAME, COLUMN_TYPE AS TYPE, COLUMN_KEY AS `KEY`, IS_NULLABLE AS `NULLABLE`, COLUMN_DEFAULT AS `DEFAULT`, EXTRA AS EXTRA, COLUMN_COMMENT AS COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '?' AND TABLE_NAME = '?'";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }



}
