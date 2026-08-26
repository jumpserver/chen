package org.jumpserver.chen.framework.console.transaction;

import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;

@Slf4j
public final class QueryTransactionStateInspector {
    private final Connection connection;
    private final TransactionStateProbe probe;

    public static QueryTransactionStateInspector create(DbType dbType, Connection connection) {
        TransactionStateProbe probe = switch (dbType) {
            case postgresql -> new PostgresqlTransactionStateProbe();
            case mysql -> new MysqlDriverTransactionStateProbe();
            case mariadb -> new MysqlTransactionStateProbe();
            case oracle -> new OracleTransactionStateProbe();
            case sqlserver -> new SqlServerTransactionStateProbe();
            case dm -> new DamengTransactionStateProbe();
            case db2 -> new Db2TransactionStateProbe();
            default -> ignored -> QueryTransactionState.UNKNOWN;
        };
        return new QueryTransactionStateInspector(connection, probe);
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
            log.debug("probe transaction state failed", e);
            return QueryTransactionProbeResult.failed();
        }
    }
}
