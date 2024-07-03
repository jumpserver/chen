package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class MysqlActuator extends BaseSQLActuator {
    public MysqlActuator(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    public MysqlActuator(MysqlActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }

    @Override
    public String getCurrentSchema() throws SQLException {
        var result = this.execute(SQL.of("SELECT DATABASE()"));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public List<String> getSchemas() throws SQLException {
        var result = this.execute(SQL.of("show databases"));
        return result.getData().stream().map(row -> (String) row.get(0)).toList();
    }

    @Override
    public void changeSchema(String schema) throws SQLException {
        this.execute(SQL.of("use `?`", schema));
    }


    @Override
    public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) throws SQLException {
        var sql = SQL.of("select * from `?`.`?`", schema, table);
        return this.createPlan(sql, sqlQueryParams);
    }
}
