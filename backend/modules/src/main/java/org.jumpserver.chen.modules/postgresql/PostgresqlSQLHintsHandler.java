package org.jumpserver.chen.modules.postgresql;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLHintsHandler;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.utils.TreeUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class PostgresqlSQLHintsHandler extends BaseSQLHintsHandler {

    private final ConnectionManager connectionManager;


    public PostgresqlSQLHintsHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }


    private static final String SQL_GET_ALL_TABLES = "SELECT TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '?'";

    public List<Table> getALlTables(String schema) throws SQLException {
        return this.connectionManager.getSqlActuator()
                .getObjects(SQL.of(SQL_GET_ALL_TABLES, schema)
                        .getSql(), Table.class, Map.of("name", 1, "schema", 2));
    }

    private static final String SQL_GET_ALL_FIELDS = "SELECT COLUMN_NAME,TABLE_SCHEMA,TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA= '?'";


    public List<Field> getAllFields(String schema) throws SQLException {
        return this.connectionManager
                .getSqlActuator()
                .getObjects(SQL.of(SQL_GET_ALL_FIELDS, schema).getSql(), Field.class, Map.of("name", 1, "schema", 2, "table", 3));

    }


    @Override
    public Map<String, List<String>> getHints(String nodeKey, String context) throws SQLException {

        var db = TreeUtils.getValue(nodeKey, "database");
        if (StringUtils.isNotEmpty(db)) {
            this.connectionManager.setDatabaseContext(db);
        }

        return super.getHints(nodeKey, context);
    }

}
