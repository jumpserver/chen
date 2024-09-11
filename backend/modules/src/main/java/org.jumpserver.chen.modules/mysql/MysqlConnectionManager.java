package org.jumpserver.chen.modules.mysql;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.modules.base.ssl.JKSGenerator;

import java.sql.SQLException;
import java.util.Properties;

public class MysqlConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:mysql://${host}:${port}/${db}?useSSL=false&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=CONVERT_TO_NULL&tinyInt1isBit=false";
    private String jdbcUrl;

    public MysqlConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new MysqlActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
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

            props.setProperty("useSSL", "true");
            props.setProperty("requireSSL", "true");

            var jksGenerator = new JKSGenerator();
            if ((boolean) this.getConnectInfo().getOptions().get("verifyServerCertificate")) {
                props.setProperty("verifyServerCertificate", "true");
                jksGenerator.setCaCert((String) this.getConnectInfo().getOptions().get("caCert"));

                var caCertPath = jksGenerator.generateCaJKS();
                props.setProperty("trustCertificateKeyStoreUrl", "file:" + caCertPath);
                props.setProperty("trustCertificateKeyStorePassword", JKSGenerator.JSK_PASS);

            }
            if (StringUtils.isNotBlank((String) this.getConnectInfo().getOptions().get("clientCert"))) {
                jksGenerator.setClientCert((String) this.getConnectInfo().getOptions().get("clientCert"));
                jksGenerator.setClientKey((String) this.getConnectInfo().getOptions().get("clientKey"));
                var clientCertPath = jksGenerator.generateClientJKS();
                props.setProperty("clientCertificateKeyStoreUrl", "file:" + clientCertPath);
                props.setProperty("clientCertificateKeyStorePassword", JKSGenerator.JSK_PASS);
                props.setProperty("clientKeyPassword", JKSGenerator.JSK_PASS);
            }
        }
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
