package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLIdentifier;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class SQLServerActuator extends BaseSQLActuator {
    public SQLServerActuator(ConnectionManager druidDataSource) {
        super(druidDataSource);
    }

    public SQLServerActuator(SQLServerActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }

    @Override
    public String getCurrentSchema() throws SQLException {
        var result = this.execute(SQL.of("SELECT DB_NAME()"));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public List<String> getSchemas() throws SQLException {
        var result = this.execute(SQL.of("SELECT name FROM sys.databases"));
        return result.getData().stream().map(row -> (String) row.get(0)).toList();
    }

    @Override
    public void changeSchema(String schema) throws SQLException {
        var identifier = SQLIdentifier.quote(this.getDbType(), schema);
        this.execute(SQL.of("USE " + identifier));
    }

    @Override
    public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) throws SQLException {
        var sql = SQL.of("select * from ?.[?]", schema, table);
        return this.createPlan(sql, sqlQueryParams);
    }
}
