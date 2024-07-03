package org.jumpserver.chen.framework.datasource.base;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.framework.driver.DriverManager;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.ssl.JKSGenerator;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public abstract class BaseConnectionManager implements ConnectionManager {

    private final DBConnectInfo connectInfo;
    @Getter
    private final Datasource datasource;
    private final Map<String, DruidDataSource> dataSourceMap = new HashMap<>();
    private final ThreadLocal<String> currentDatabase = new ThreadLocal<>();

    protected SQLActuator sqlActuator;

    public BaseConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        this.datasource = datasource;
        this.connectInfo = connectInfo;
    }

    public void ping(String jdbcUrl, Properties props) throws SQLException {
        props.setProperty("user", this.getConnectInfo().getUser());
        if (StringUtils.isNotBlank(this.getConnectInfo().getPassword())) {
            props.setProperty("password", this.getConnectInfo().getPassword());
        }
        this.setSSLProps(props);
        this.getDriver().connect(jdbcUrl, props).close();
    }

    public void ping(String jdbcUrl) throws SQLException {
        Properties props = new Properties();
        this.ping(jdbcUrl, props);
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


    public List<DriverClassLoader> getDriverClassLoaders() {
        var driverClassLoaders = DriverManager.getDrivers(this.connectInfo.getDbType());
        if (driverClassLoaders == null) {
            throw new RuntimeException("driver not found");
        }
        return driverClassLoaders;
    }

    public Driver getDriver() {
        var driverClassLoaders = this.getDriverClassLoaders();
        for (ClassLoader classLoader : driverClassLoaders) {
            try {
                return (Driver) classLoader.loadClass(this.getDriverClassName()).getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException |
                     InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("driver not load");
    }

    @Override
    public void setDatabaseContext(String database) {
        this.currentDatabase.set(database);
    }

    @Override
    public String getContextKey() {
        return "schema";
    }

    @Override
    public DBConnectInfo getConnectInfo() {
        return this.connectInfo;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.getOrInitDataSource(this.getCurrentDatabaseName()).getConnection();
    }

    @Override
    public Connection getPhysicalConnection() throws SQLException {
        return this.getOrInitDataSource(this.getCurrentDatabaseName())
                .createPhysicalConnection()
                .getPhysicalConnection();
    }

    private String getCurrentDatabaseName() {
        return this.currentDatabase.get() == null ? this.connectInfo.getDb() : this.currentDatabase.get();
    }

    @Override
    public SQLActuator getSqlActuator() {
        return this.sqlActuator;
    }

    @Override
    public void close() {
        for (DruidDataSource dataSource : dataSourceMap.values()) {
            dataSource.close();
        }
    }

    public DruidDataSource getOrInitDataSource(String database) throws SQLException {
        if (StringUtils.isEmpty(database)) {
            database = this.connectInfo.getDb();
        }
        if (this.dataSourceMap.containsKey(database)) {
            return this.dataSourceMap.get(database);
        }
        DruidDataSource ds = new DruidDataSource();

        var properties = new Properties();
        this.setSSLProps(properties);

        this.connectInfo.getOptions().forEach((k, v) -> properties.setProperty(k, v.toString()));

        ds.setConnectProperties(properties);

        ds.setDriver(this.getDriver());
        ds.setUrl(this.getJDBCUrl(database));
        ds.setUsername(connectInfo.getUser());

        if (StringUtils.isNotBlank(connectInfo.getPassword())) {
            ds.setPassword(connectInfo.getPassword());
        }

        ds.setKeepAlive(true);
        ds.setFailFast(true);
        ds.setKillWhenSocketReadTimeout(false);
        ds.setTestWhileIdle(true);
        ds.setSocketTimeout(1000 * 60 * 120);
        ds.init();

        try {
            ds.getConnection().close();
        } catch (SQLException e) {
            ds.close();
            throw new SQLException(String.format("%s : %s", MessageUtils.get("msg.error.connect_error"), e.getMessage()));
        }
        this.dataSourceMap.put(database, ds);
        return ds;
    }
}
