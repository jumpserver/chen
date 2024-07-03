package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Schema;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.entity.resource.View;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;


public class OracleResourceBrowser extends BaseResourceBrowser {
    public OracleResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new OracleSQLHintsHandler(connectionManager));
    }

    private static final String SQL_GET_SCHEMAS = "SELECT USERNAME FROM ALL_USERS";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = "SELECT TABLE_NAME FROM ALL_TABLES where TABLESPACE_NAME is not null AND OWNER='?'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "SELECT VIEW_NAME  FROM ALL_VIEWS WHERE  OWNER= '?'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,CHAR_LENGTH,CHAR_USED,DATA_DEFAULT,COMMENTS FROM ALL_TAB_COLUMNS WHERE OWNER='?' AND TABLE_NAME='?'" ;

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }


}
