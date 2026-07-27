package org.jumpserver.chen.framework.console.transaction;

import com.alibaba.druid.DbType;

import java.sql.Connection;

public final class QueryTransactionStateInspector {
    private final Connection connection;
    private final TransactionStateProbe probe;

    public static QueryTransactionStateInspector create(DbType dbType, Connection connection) {
        TransactionStateProbe probe = switch (dbType) {
            case postgresql -> new PostgresqlTransactionStateProbe();
            case mysql, mariadb -> new MysqlTransactionStateProbe();
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
        return this.probe.inspectResult(this.connection);
    }
}
