package org.jumpserver.chen.modules.clickhouse;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;

public class ClickhouseConnectionManager extends BaseConnectionManager {

    // The bundled slim JDBC driver does not include an LZ4 implementation.
    // Disable HTTP response compression until Chen ships the shaded driver.
    private static final String jdbcUrlTemplate = "jdbc:clickhouse://${host}:${port}/${db}?compress=0";
    private String jdbcUrl;

    public ClickhouseConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new ClickhouseActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "com.clickhouse.jdbc.ClickHouseDriver";
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
        // ClickHouse exposes databases through schema nodes, like MySQL/MariaDB.
        return "schema";
    }

    @Override
    public String getJDBCUrl(String database) {
        if (StringUtils.isBlank(database)) {
            return this.jdbcUrl;
        }
        return this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate, database);
    }
}
