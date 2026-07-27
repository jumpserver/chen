package org.jumpserver.chen.framework.console.transaction;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLBeginStatement;
import com.alibaba.druid.sql.ast.statement.SQLCommitStatement;
import com.alibaba.druid.sql.ast.statement.SQLRollbackStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLStartTransactionStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGStartTransactionStatement;

import java.sql.Connection;
import java.util.Locale;

public final class QueryTransactionStateTracker {
    private final DatabaseFamily databaseFamily;
    private final Connection connection;
    private final TransactionStateProbe probe;
    private volatile QueryTransactionState state;
    private boolean manualCommit;

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
        this.state = this.inspect();
        this.manualCommit = this.state == QueryTransactionState.MANUAL_COMMIT_IDLE;
    }

    public QueryTransactionState currentState() {
        return this.state;
    }

    public synchronized void afterExecution(SQLStatement statement, boolean successful) {
        if (this.databaseFamily == DatabaseFamily.UNSUPPORTED) {
            this.state = QueryTransactionState.UNKNOWN;
            return;
        }

        this.state = this.transition(statement, successful);
        QueryTransactionState observed = this.inspect();
        if (observed == QueryTransactionState.UNKNOWN) {
            this.state = QueryTransactionState.UNKNOWN;
            return;
        }
        this.state = observed;
        this.manualCommit = observed == QueryTransactionState.MANUAL_COMMIT_IDLE
                || (this.manualCommit && (observed == QueryTransactionState.TRANSACTION_ACTIVE
                || observed == QueryTransactionState.TRANSACTION_FAILED));
    }

    private QueryTransactionState transition(SQLStatement statement, boolean successful) {
        if (!successful) {
            if (this.databaseFamily == DatabaseFamily.POSTGRESQL
                    && (this.state == QueryTransactionState.TRANSACTION_ACTIVE
                    || this.state == QueryTransactionState.TRANSACTION_FAILED)) {
                return QueryTransactionState.TRANSACTION_FAILED;
            }
            return this.state;
        }

        if (statement instanceof SQLBeginStatement
                || statement instanceof SQLStartTransactionStatement
                || statement instanceof PGStartTransactionStatement) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        if (statement instanceof SQLCommitStatement) {
            return this.idleState();
        }
        if (statement instanceof SQLRollbackStatement rollbackStatement) {
            if (rollbackStatement.getTo() != null) {
                return QueryTransactionState.TRANSACTION_ACTIVE;
            }
            return this.idleState();
        }
        if (statement instanceof SQLSetStatement setStatement) {
            Boolean autoCommit = findAutoCommit(setStatement);
            if (autoCommit != null) {
                this.manualCommit = !autoCommit;
                return autoCommit
                        ? QueryTransactionState.AUTO_COMMIT
                        : this.state == QueryTransactionState.TRANSACTION_ACTIVE
                        ? QueryTransactionState.TRANSACTION_ACTIVE
                        : QueryTransactionState.MANUAL_COMMIT_IDLE;
            }
        }
        if (this.databaseFamily == DatabaseFamily.MYSQL) {
            return this.state == QueryTransactionState.AUTO_COMMIT
                    ? QueryTransactionState.AUTO_COMMIT
                    : QueryTransactionState.UNKNOWN;
        }
        if (this.state == QueryTransactionState.MANUAL_COMMIT_IDLE) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        return this.state;
    }

    private QueryTransactionState idleState() {
        return this.manualCommit
                ? QueryTransactionState.MANUAL_COMMIT_IDLE
                : QueryTransactionState.AUTO_COMMIT;
    }

    private QueryTransactionState inspect() {
        return this.probe.inspect(this.connection);
    }

    private static Boolean findAutoCommit(SQLSetStatement statement) {
        for (var item : statement.getItems()) {
            String target = normalize(item.getTarget());
            if (!"autocommit".equals(target)) {
                continue;
            }
            SQLExpr value = item.getValue();
            if (value instanceof SQLIntegerExpr integer) {
                int intValue = integer.getNumber().intValue();
                if (intValue == 0 || intValue == 1) {
                    return intValue == 1;
                }
            }
            String normalizedValue = normalize(value);
            if ("on".equals(normalizedValue) || "true".equals(normalizedValue)) {
                return true;
            }
            if ("off".equals(normalizedValue) || "false".equals(normalizedValue)) {
                return false;
            }
        }
        return null;
    }

    private static String normalize(SQLExpr expression) {
        String value = expression.toString()
                .replace("`", "")
                .replace("\"", "")
                .replace("@", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        int separator = value.lastIndexOf('.');
        return separator >= 0 ? value.substring(separator + 1) : value;
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
