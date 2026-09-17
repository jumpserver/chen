package org.jumpserver.chen.framework.datasource;

import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;

import java.sql.Connection;
import java.sql.SQLException;

public interface ConnectionManager {
    String getDriverClassName();
    void ping() throws SQLException;

    String getVersion() throws SQLException;

    String getJDBCUrl();

    String getDisplayJDBCUrl();

    String getJDBCUrl(String database);

    void setDatabaseContext(String database);

    <T> T withDatabaseContext(String database, DatabaseContextAction<T> action) throws SQLException;

    Datasource getDatasource();

    DBConnectInfo getConnectInfo();

    Connection getConnection() throws SQLException;

    Connection getPhysicalConnection() throws SQLException;

    SQLActuator getSqlActuator();

    // 对象树/编辑器当前上下文使用的 key，例如 schema。
    String getContextKey();

    // JDBC URL/连接池默认库使用的 key，不能和对象树上下文混用。
    String getDatabaseContextKey();

    void close();

    @FunctionalInterface
    interface DatabaseContextAction<T> {
        T run() throws SQLException;
    }
}
