package org.jumpserver.chen.modules.db2;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class DB2ResourceBrowser extends BaseResourceBrowser {
    public DB2ResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new DB2SQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "select SCHEMANAME AS NAME from syscat.schemata";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "select TABNAME from syscat.TABLES where TABSCHEMA = '?' and TYPE = 'T'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "select TABNAME from syscat.TABLES where TABSCHEMA = '?' and TYPE = 'V'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "select COLNAME, TYPENAME, LENGTH, DEFAULT, REMARKS from syscat.COLUMNS where TABSCHEMA = '?' and TABNAME = '?'";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }



}
