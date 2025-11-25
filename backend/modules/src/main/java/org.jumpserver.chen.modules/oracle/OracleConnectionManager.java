package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OracleConnectionManager extends BaseConnectionManager {

    private static final String sidTemplate = "jdbc:oracle:thin:@${host}:${port}:${db}?oracle.jdbc.timezoneAsRegion=false&useUnicode=true&characterEncoding=UTF-8&oracle.jdbc.J2EE13Compliant=true";
    private static final String serviceTemplate = "jdbc:oracle:thin:@//${host}:${port}/${db}?oracle.jdbc.timezoneAsRegion=false&useUnicode=true&characterEncoding=UTF-8&oracle.jdbc.J2EE13Compliant=true";
    private static final long PING_TIMEOUT_SECONDS = 20;
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
        // Keep original host (FQDN). Forcing InetAddress resolution can pick an
        // unreachable public A record while the hostname itself is reachable.
        // Hang fix = connect/read timeouts + first-success-wins (do not wait for SID).

        var sidUrl = this.getConnectInfo().toJDBCUrl(sidTemplate);
        var serviceUrl = this.getConnectInfo().toJDBCUrl(serviceTemplate);

        Properties props = new Properties();
        if (this.getConnectInfo().getOptions().containsKey("internal_logon")) {
            props.setProperty("internal_logon", this.getConnectInfo().getOptions().get("internal_logon").toString());
        }
        // Fail fast instead of blocking forever in Driver.connect().
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        props.setProperty("oracle.jdbc.ReadTimeout", "15000");

        var pool = Executors.newFixedThreadPool(2);

        CompletableFuture<ConnectionAttempt> f1 = CompletableFuture.supplyAsync(
                () -> this.attemptConnection(sidUrl, copyProps(props)), pool
        );

        CompletableFuture<ConnectionAttempt> f2 = CompletableFuture.supplyAsync(
                () -> this.attemptConnection(serviceUrl, copyProps(props)), pool
        );

        try {
            ConnectionAttempt winner = waitForFirstSuccess(f1, f2, PING_TIMEOUT_SECONDS);
            if (winner != null && winner.jdbcUrl() != null) {
                this.jdbcUrl = winner.jdbcUrl();
                return;
            }
            var sidAttempt = peekAttempt(f1);
            var serviceNameAttempt = peekAttempt(f2);
            throw connectionFailed(sidAttempt.error(), serviceNameAttempt.error());
        } finally {
            f1.cancel(true);
            f2.cancel(true);
            pool.shutdownNow();
        }
    }

    /**
     * Return as soon as either probe succeeds. Do not wait for the other
     * (SID+FQDN may hang inside ojdbc even with timeouts).
     */
    private static ConnectionAttempt waitForFirstSuccess(
            CompletableFuture<ConnectionAttempt> f1,
            CompletableFuture<ConnectionAttempt> f2,
            long timeoutSeconds
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (f1.isDone()) {
                var a = peekAttempt(f1);
                if (a.jdbcUrl() != null) {
                    return a;
                }
            }
            if (f2.isDone()) {
                var a = peekAttempt(f2);
                if (a.jdbcUrl() != null) {
                    return a;
                }
            }
            if (f1.isDone() && f2.isDone()) {
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static Properties copyProps(Properties props) {
        Properties copy = new Properties();
        copy.putAll(props);
        return copy;
    }

    private static ConnectionAttempt peekAttempt(CompletableFuture<ConnectionAttempt> future) {
        if (!future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
            return new ConnectionAttempt(null, new SQLException("Oracle probe timed out or was cancelled"));
        }
        try {
            return future.getNow(new ConnectionAttempt(null, new SQLException("Oracle probe returned empty")));
        } catch (Exception e) {
            return new ConnectionAttempt(null, new SQLException(e.getMessage(), e));
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
        var sidMsg = sidError != null ? sidError.getMessage() : "unknown";
        var snMsg = serviceNameError != null ? serviceNameError.getMessage() : "unknown";
        var message = Objects.equals(sidMsg, snMsg)
                ? sidMsg
                : "SID: %s; ServiceName: %s".formatted(sidMsg, snMsg);
        var error = new RuntimeException(message, sidError);
        if (serviceNameError != null) {
            error.addSuppressed(serviceNameError);
        }
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
