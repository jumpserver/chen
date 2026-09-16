package org.jumpserver.chen.framework.console.transaction;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.plan.PlanDatabase;

import java.sql.Connection;

@Slf4j
public final class QueryTransactionStateInspector {
    private final Connection connection;
    private final TransactionStateProbe probe;

    public static QueryTransactionStateInspector create(PlanDatabase database, Connection connection) {
        return new QueryTransactionStateInspector(connection, probeFor(database));
    }

    static TransactionStateProbe probeFor(PlanDatabase database) {
        if (database == null) {
            return ignored -> QueryTransactionState.UNKNOWN;
        }
        return switch (database) {
            case postgresql -> new PostgresqlTransactionStateProbe();
            case mysql -> new MysqlDriverTransactionStateProbe();
            case mariadb -> new MysqlTransactionStateProbe();
            case oracle -> new OracleTransactionStateProbe();
            case sqlserver -> new SqlServerTransactionStateProbe();
            case dm -> new DamengTransactionStateProbe();
            case db2 -> new Db2TransactionStateProbe();
            default -> ignored -> QueryTransactionState.UNKNOWN;
        };
    }

    QueryTransactionStateInspector(Connection connection, TransactionStateProbe probe) {
        this.connection = connection;
        this.probe = probe;
    }

    public QueryTransactionProbeResult probeNow() {
        try {
            return QueryTransactionProbeResult.observed(this.probe.inspect(this.connection));
        } catch (TransactionStateProbeException e) {
            // Only explicit probe failures (SQLException / driver-compat reflection errors wrapped
            // by the probe) become a probeFailed result. Program errors such as NPE,
            // ClassCastException or IllegalStateException are NOT caught here and must propagate.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn(
                    "probe transaction state failed: connectionClass={}, classLoader={}, cause={}",
                    this.connection == null ? "null" : this.connection.getClass().getName(),
                    this.connection == null ? "null" : PostgresqlTransactionStateProbe.classLoaderName(this.connection),
                    cause.toString()
            );
            return QueryTransactionProbeResult.failed(summarize(cause));
        }
    }

    private static String summarize(Throwable cause) {
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
