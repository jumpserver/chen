package org.jumpserver.chen.framework.console.transaction;

import java.sql.Connection;
import java.util.Locale;

public final class QueryTransactionStateTracker {
    private final DatabaseFamily databaseFamily;
    private final Connection connection;
    private final TransactionStateProbe probe;
    private volatile QueryTransactionState state;

    public static QueryTransactionStateTracker create(String databaseType, Connection connection) {
        DatabaseFamily family = DatabaseFamily.from(databaseType);
        TransactionStateProbe probe = switch (family) {
            case POSTGRESQL -> new PostgresqlTransactionStateProbe();
            case MYSQL -> new MysqlTransactionStateProbe();
            case ORACLE -> new OracleTransactionStateProbe();
            case SQL_SERVER -> new SqlServerTransactionStateProbe();
            case DAMENG -> new DamengTransactionStateProbe();
            case DB2 -> new Db2TransactionStateProbe();
            case UNSUPPORTED -> ignored -> QueryTransactionState.UNKNOWN;
        };
        return new QueryTransactionStateTracker(family, connection, probe);
    }

    QueryTransactionStateTracker(DatabaseFamily databaseFamily,
                                 Connection connection,
                                 TransactionStateProbe probe) {
        this.databaseFamily = databaseFamily;
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
        if (this.databaseFamily == DatabaseFamily.UNSUPPORTED) {
            return QueryTransactionProbeResult.observed(QueryTransactionState.UNKNOWN);
        }
        return this.probe.inspectResult(this.connection);
    }

    enum DatabaseFamily {
        POSTGRESQL,
        MYSQL,
        ORACLE,
        SQL_SERVER,
        DAMENG,
        DB2,
        UNSUPPORTED;

        static DatabaseFamily from(String databaseType) {
            if (databaseType == null) {
                return UNSUPPORTED;
            }
            return switch (databaseType.toLowerCase(Locale.ROOT)) {
                case "postgresql" -> POSTGRESQL;
                case "mysql", "mariadb" -> MYSQL;
                case "oracle" -> ORACLE;
                case "sqlserver", "sql_server", "mssql" -> SQL_SERVER;
                case "dameng", "dm" -> DAMENG;
                case "db2" -> DB2;
                default -> UNSUPPORTED;
            };
        }
    }
}
