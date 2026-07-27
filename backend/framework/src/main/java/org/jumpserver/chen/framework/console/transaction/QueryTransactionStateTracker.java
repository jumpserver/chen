package org.jumpserver.chen.framework.console.transaction;

import com.alibaba.druid.DbType;

import java.sql.Connection;

public final class QueryTransactionStateTracker {
    private final Connection connection;
    private final TransactionStateProbe probe;
    private volatile QueryTransactionState state;

    public static QueryTransactionStateTracker create(DbType dbType, Connection connection) {
        TransactionStateProbe probe = switch (dbType) {
            case postgresql -> new PostgresqlTransactionStateProbe();
            case mysql, mariadb -> new MysqlTransactionStateProbe();
            case oracle -> new OracleTransactionStateProbe();
            case sqlserver -> new SqlServerTransactionStateProbe();
            case dm -> new DamengTransactionStateProbe();
            case db2 -> new Db2TransactionStateProbe();
            default -> ignored -> QueryTransactionState.UNKNOWN;
        };
        return new QueryTransactionStateTracker(connection, probe);
    }

    QueryTransactionStateTracker(Connection connection,
                                 TransactionStateProbe probe) {
        this.connection = connection;
        this.probe = probe;
        this.state = this.inspect().state();
    }

    public QueryTransactionState currentState() {
        return this.state;
    }

    public synchronized QueryTransactionProbeResult probeNow() {
        QueryTransactionProbeResult result = this.inspect();
        this.state = result.state();
        return result;
    }

    private QueryTransactionProbeResult inspect() {
        return this.probe.inspectResult(this.connection);
    }
}
