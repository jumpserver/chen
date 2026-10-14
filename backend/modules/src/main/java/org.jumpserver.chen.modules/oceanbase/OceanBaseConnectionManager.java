package org.jumpserver.chen.modules.oceanbase;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.modules.mysql.MysqlActuator;
import org.jumpserver.chen.modules.oracle.OracleActuator;
import org.jumpserver.chen.modules.base.ssl.JKSGenerator;

import java.sql.SQLException;
import java.util.Properties;

public class OceanBaseConnectionManager extends BaseConnectionManager {
    private static final String URL_TEMPLATE =
            "jdbc:oceanbase://${host}:${port}/${db}?useUnicode=true&characterEncoding=UTF-8";
    private final boolean oracleMode;
    private String jdbcUrl;

    public OceanBaseConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.oracleMode = "oracle".equals(connectInfo.getTenantMode());
        this.sqlActuator = oracleMode ? new OracleActuator(this) : new MysqlActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "com.oceanbase.jdbc.Driver";
    }

    @Override
    public void ping() throws SQLException {
        var url = getConnectInfo().toJDBCUrl(URL_TEMPLATE);
        ping(url);
        jdbcUrl = url;
    }

    @Override
    protected void setSSLProps(Properties props) {
        if (Boolean.TRUE.equals(getConnectInfo().getOptions().get("useSSL"))) {
            props.setProperty("useSSL", "true");
            var verify = Boolean.TRUE.equals(getConnectInfo().getOptions().get("verifyServerCertificate"));
            props.setProperty("trustServerCertificate", String.valueOf(!verify));
            var caCert = (String) getConnectInfo().getOptions().get("caCert");
            var clientCert = (String) getConnectInfo().getOptions().get("clientCert");
            var clientKey = (String) getConnectInfo().getOptions().get("clientKey");
            if (verify && StringUtils.isNotBlank(caCert)) {
                var generator = new JKSGenerator();
                generator.setCaCert(caCert);
                props.setProperty("trustStore", generator.generateCaJKS().toString());
                props.setProperty("trustStorePassword", JKSGenerator.JSK_PASS);
            }
            if (StringUtils.isNotBlank(clientCert)) {
                var generator = new JKSGenerator();
                generator.setClientCert(clientCert);
                generator.setClientKey(clientKey);
                props.setProperty("keyStore", generator.generateClientJKS().toString());
                props.setProperty("keyStorePassword", JKSGenerator.JSK_PASS);
                props.setProperty("keyPassword", JKSGenerator.JSK_PASS);
            }
        }
    }

    @Override
    public String getVersion() throws SQLException {
        var query = oracleMode ? "SELECT BANNER FROM V$VERSION" : "SELECT VERSION()";
        var result = sqlActuator.execute(SQL.of(query));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public String getJDBCUrl() {
        return jdbcUrl;
    }

    @Override
    public String getDisplayJDBCUrl() {
        return getConnectInfo().toDisplayJDBCUrl(URL_TEMPLATE);
    }

    @Override
    public String getDatabaseContextKey() {
        return oracleMode ? "database" : "schema";
    }

    @Override
    public String getJDBCUrl(String database) {
        if (oracleMode || StringUtils.isBlank(database)) {
            return jdbcUrl;
        }
        return getConnectInfo().toJDBCUrl(URL_TEMPLATE, database);
    }
}
