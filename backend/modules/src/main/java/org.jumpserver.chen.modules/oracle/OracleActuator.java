package org.jumpserver.chen.modules.oracle;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class OracleActuator extends BaseSQLActuator {
    public OracleActuator(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    public OracleActuator(OracleActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }

    @Override
    public String getCurrentSchema() throws SQLException {
        var result = this.execute(SQL.of("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL"));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public List<String> getSchemas() throws SQLException {
        var result = this.execute(SQL.of("SELECT USERNAME FROM ALL_USERS"));
        return result.getData().stream().map(row -> (String) row.get(0)).toList();
    }

    @Override
    public void changeSchema(String schema) throws SQLException {
        this.execute(SQL.of("ALTER SESSION SET CURRENT_SCHEMA = ?", schema));
    }

    @Override
    public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) throws SQLException {
        var sql = SQL.of("select * from ?.\"?\"", schema, table);
        return this.createPlan(sql, sqlQueryParams);
    }


    private void beforeCreatePlan(SQL sql){
        if (sql.getSql().endsWith(";")) {
            sql.setSql(sql.getSql().substring(0, sql.getSql().length() - 1));
        }
    }
    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams params) throws SQLException {
        this.beforeCreatePlan(sql);
        return super.createPlan(sql, params);
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql) throws SQLException {
        this.beforeCreatePlan(sql);
        return super.createPlan(sql);
    }

    @Override
    public List<String> parseSQL(SQL sql) {
        return SQLUtils.parseStatements(sql.getSql(), DbType.ali_oracle).stream()
                .map(stmt -> SQLUtils.toSQLString(stmt, DbType.ali_oracle))
                .toList();
    }
}
