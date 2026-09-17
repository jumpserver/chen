package org.jumpserver.chen.modules.mariadb;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;

public class MariaDBConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:mariadb://${host}:${port}/${db}?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=CONVERT_TO_NULL";
    private String jdbcUrl;

    public MariaDBConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new MariaDBActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public void ping() throws SQLException {
        var url = this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate);
        this.ping(url);
        this.jdbcUrl = url;
    }

    @Override
    public String getVersion() throws SQLException {
        var result = this.sqlActuator.execute(SQL.of("select version()"));
        return (String) result.getData().get(0).get(0);
    }


    @Override
    public String getJDBCUrl() {
        return this.jdbcUrl;
    }

    @Override
    public String getDisplayJDBCUrl() {
        return this.getConnectInfo().toDisplayJDBCUrl(jdbcUrlTemplate);
    }

    @Override
    public String getDatabaseContextKey() {
        // MariaDB 的对象树使用 schema 节点表示 database，连接池默认库也要取这个值。
        return "schema";
    }

    @Override
    public String getJDBCUrl(String database) {
        if (StringUtils.isBlank(database)) {
            return this.jdbcUrl;
        }
        // 连接池不能依赖上一条连接执行过 USE，这里显式把 database 放进 JDBC URL。
        return this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate, database);
    }
}
