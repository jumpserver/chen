package org.jumpserver.chen.modules.postgresql;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.Properties;

public class PostgresqlConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:postgresql://${host}:${port}/${db}?useUnicode=true&characterEncoding=UTF-8";
    private String jdbcUrl;

    public PostgresqlConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new PostgresqlActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "org.postgresql.Driver";
    }


    @Override
    public void ping() throws SQLException {
        var url = this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate);
        this.ping(url);
        this.jdbcUrl = url;
    }

    protected void setSSLProps(Properties props) {
        if (this.getConnectInfo().getOptions().get("useSSL") != null
                && (boolean) this.getConnectInfo().getOptions().get("useSSL")) {

            var caCertPath = (String) this.getConnectInfo().getOptions().get("caCert");
            var clientCertPath = (String) this.getConnectInfo().getOptions().get("clientCert");
            var clientKeyPath = (String) this.getConnectInfo().getOptions().get("clientKey");

            props.setProperty("ssl", "true");
            props.setProperty("sslmode", "verify-full");
            props.setProperty("sslrootcert", caCertPath);
            props.setProperty("sslcert", clientCertPath);
            props.setProperty("sslkey", clientKeyPath);
        }
    }

    private static final String SQL_GET_VERSION = "SELECT version()";

    @Override
    public String getVersion() throws SQLException {
        var result = this.sqlActuator.execute(SQL.of(SQL_GET_VERSION));
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
        return this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate, database);
    }
}
