package org.jumpserver.chen.modules.clickhouse;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLHintsHandler;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class ClickhouseSQLHintsHandler extends BaseSQLHintsHandler {
    private final ConnectionManager connectionManager;

    public ClickhouseSQLHintsHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static final String SQL_GET_SCHEMA_ALL_FIELDS = "select column_name as name, table_schema,table_name from information_schema.columns where table_schema = '?'";

    public List<Field> getAllFields(String schema) throws SQLException {
        return this.connectionManager
                .getSqlActuator()
                .getObjects(SQL.of(SQL_GET_SCHEMA_ALL_FIELDS, schema).getSql(), Field.class, Map.of("name", 1, "schema", 2, "table", 3));

    }

    private static final String SQL_GET_ALL_TABLES = "select table_name as name, table_schema  from information_schema.tables where table_schema='?' ";

    public List<Table> getALlTables(String context) throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(SQL_GET_ALL_TABLES, context)
                        .getSql(), Table.class, Map.of("name", 1, "schema", 2));
    }
}
