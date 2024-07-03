package org.jumpserver.chen.modules.dameng;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLHintsHandler;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class DMSQLHintsHandler extends BaseSQLHintsHandler {
    private final ConnectionManager connectionManager;

    public DMSQLHintsHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static final String SQL_GET_ALL_TABLES = "SELECT TABLE_NAME,OWNER FROM ALL_TABLES  WHERE OWNER= '?'";

    public List<Table> getALlTables(String context) throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(SQL_GET_ALL_TABLES, context)
                        .getSql(), Table.class, Map.of("name", 1, "schema", 2));
    }

    private static final String SQL_GET_SCHEMA_ALL_FIELDS = "SELECT COLUMN_NAME,OWNER,TABLE_NAME FROM ALL_TAB_COLUMNS WHERE OWNER = '?'";

    public List<Field> getAllFields(String schema) throws SQLException {
        return this.connectionManager
                .getSqlActuator()
                .getObjects(SQL.of(SQL_GET_SCHEMA_ALL_FIELDS, schema).getSql(), Field.class, Map.of("name", 1, "schema", 2, "table", 3));

    }
}
