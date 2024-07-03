package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class MysqlResourceBrowser extends BaseResourceBrowser {
    public MysqlResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new MysqlSQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "SELECT SCHEMA_NAME AS NAME FROM INFORMATION_SCHEMA.SCHEMATA";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "SELECT TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '?' AND TABLE_TYPE != 'VIEW'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "SELECT TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '?' AND TABLE_TYPE = 'VIEW'";

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
