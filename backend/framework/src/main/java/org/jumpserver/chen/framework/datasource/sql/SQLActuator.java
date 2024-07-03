package org.jumpserver.chen.framework.datasource.sql;


import com.alibaba.druid.DbType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface SQLActuator {

    DbType getDbType();
    int getAffectedRows(SQL sql) throws SQLException;

    List<String> parseSQL(SQL sql);

    <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) throws SQLException;

    int count(SQL sql) throws SQLException;

    int count(SQLExecutePlan plan) throws SQLException;

    SQLQueryResult execute(SQLExecutePlan plan) throws SQLException;

    SQLQueryResult execute(SQL sql) throws SQLException;

    SQLQueryResult executeWithAudit(SQLExecutePlan plan) throws SQLException;

    SQLQueryResult executeWithAudit(SQL sql) throws SQLException;

    SQLActuator withConnection(Connection connection);

    SQLExecutePlan createPlan(SQL sql) throws SQLException;

    SQLExecutePlan createPlan(SQL sql, SQLQueryParams params) throws SQLException;

    SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) throws SQLException;

    String getCurrentSchema() throws SQLException;

    List<String> getSchemas() throws SQLException;

    void changeSchema(String schema) throws SQLException;

}
