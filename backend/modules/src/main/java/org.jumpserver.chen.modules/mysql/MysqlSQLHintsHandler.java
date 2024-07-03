package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLHintsHandler;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class MysqlSQLHintsHandler extends BaseSQLHintsHandler {
    private final ConnectionManager connectionManager;

    public MysqlSQLHintsHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static final String SQL_GET_SCHEMA_ALL_FIELDS = "SELECT COLUMN_NAME AS NAME, TABLE_SCHEMA,TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '?'";

    public List<Field> getAllFields(String schema) throws SQLException {
        return this.connectionManager
                .getSqlActuator()
                .getObjects(SQL.of(SQL_GET_SCHEMA_ALL_FIELDS, schema).getSql(), Field.class, Map.of("name", 1, "schema", 2, "table", 3));

    }

    private static final String SQL_GET_ALL_TABLES = "SELECT TABLE_NAME AS NAME, TABLE_SCHEMA  FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='?' ";

    public List<Table> getALlTables(String context) throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(SQL_GET_ALL_TABLES, context)
                        .getSql(), Table.class, Map.of("name", 1, "schema", 2));
    }
}
