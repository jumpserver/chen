package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public class OracleConnectionManager extends BaseConnectionManager {

    private static final String sidTemplate = "jdbc:oracle:thin:@${host}:${port}:${db}?oracle.jdbc.timezoneAsRegion=false&useUnicode=true&characterEncoding=UTF-8&oracle.jdbc.J2EE13Compliant=true";
    private static final String serviceTemplate = "jdbc:oracle:thin:@//${host}:${port}/${db}?oracle.jdbc.timezoneAsRegion=false&useUnicode=true&characterEncoding=UTF-8&oracle.jdbc.J2EE13Compliant=true";
    private String jdbcUrl;

    public OracleConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new OracleActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "oracle.jdbc.driver.OracleDriver";
    }


    @Override
    public void ping() {
        var sidUrl = this.getConnectInfo().toJDBCUrl(sidTemplate);
        var serviceUrl = this.getConnectInfo().toJDBCUrl(serviceTemplate);

        Properties props = new Properties();
        if (this.getConnectInfo().getOptions().containsKey("internal_logon")) {
            props.setProperty("internal_logon", this.getConnectInfo().getOptions().get("internal_logon").toString());
        }

        var pool = Executors.newFixedThreadPool(2);

        CompletableFuture<ConnectionAttempt> f1 = CompletableFuture.supplyAsync(
                () -> this.attemptConnection(sidUrl, props), pool
        );

        CompletableFuture<ConnectionAttempt> f2 = CompletableFuture.supplyAsync(
                () -> this.attemptConnection(serviceUrl, props), pool
        );

        CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(f1, f2);
        try {
            combinedFuture.get(); // 等待所有Future完成
            // 判断哪个Future成功完成，并设置jdbcUrl
            var sidAttempt = f1.get();
            var serviceNameAttempt = f2.get();
            this.jdbcUrl = sidAttempt.jdbcUrl() != null ? sidAttempt.jdbcUrl() : serviceNameAttempt.jdbcUrl();
            if (this.jdbcUrl == null) {
                throw connectionFailed(sidAttempt.error(), serviceNameAttempt.error());
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error occurred while pinging database", e);
        } finally {
            pool.shutdown(); // 不要忘记关闭线程池
        }
    }

    private ConnectionAttempt attemptConnection(String jdbcUrl, Properties props) {
        try {
            this.ping(jdbcUrl, props);
            return new ConnectionAttempt(jdbcUrl, null);
        } catch (SQLException e) {
            return new ConnectionAttempt(null, e);
        }
    }

    private RuntimeException connectionFailed(SQLException sidError, SQLException serviceNameError) {
        var message = Objects.equals(sidError.getMessage(), serviceNameError.getMessage())
                ? sidError.getMessage()
                : "SID: %s; ServiceName: %s".formatted(sidError.getMessage(), serviceNameError.getMessage());
        var error = new RuntimeException(message, sidError);
        error.addSuppressed(serviceNameError);
        return error;
    }

    private record ConnectionAttempt(String jdbcUrl, SQLException error) {}

    private static final String SQL_GET_VERSION = "select concat(product,concat(version,status)) as version from product_component_version  where  product like 'Oracle%'";

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
        return this.getConnectInfo().toDisplayJDBCUrl(sidTemplate);
    }


    @Override
    public String getJDBCUrl(String database) {
        return this.jdbcUrl;
    }


}
