package org.jumpserver.chen.modules.mariadb;

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
    public String getJDBCUrl(String database) {
        return this.jdbcUrl;
    }
}
