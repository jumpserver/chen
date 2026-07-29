package org.jumpserver.chen.framework.console.transaction;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;

final class MysqlDriverTransactionStateProbe implements TransactionStateProbe {
    private static final String JDBC_CONNECTION = "com.mysql.cj.jdbc.JdbcConnection";
    private static final String SESSION = "com.mysql.cj.Session";
    private static final String SERVER_SESSION = "com.mysql.cj.protocol.ServerSession";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            ClassLoader classLoader = connection.getClass().getClassLoader();
            Class<?> jdbcConnectionClass = Class.forName(JDBC_CONNECTION, false, classLoader);
            Object jdbcConnection = jdbcConnectionClass.isInstance(connection)
                    ? connection
                    : connection.unwrap(jdbcConnectionClass);
            Object session = jdbcConnectionClass.getMethod("getSession").invoke(jdbcConnection);
            Class<?> sessionClass = Class.forName(SESSION, false, classLoader);
            Object serverSession = sessionClass.getMethod("getServerSession").invoke(session);
            Class<?> serverSessionClass = Class.forName(SERVER_SESSION, false, classLoader);
            boolean inTransaction = (boolean) serverSessionClass
                    .getMethod("inTransactionOnServer")
                    .invoke(serverSession);
            return map(inTransaction, connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException e) {
            throw new TransactionStateProbeException(e);
        }
    }

    static QueryTransactionState map(boolean inTransaction, boolean autoCommit) {
        if (inTransaction) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        return autoCommit
                ? QueryTransactionState.AUTO_COMMIT
                : QueryTransactionState.MANUAL_COMMIT_IDLE;
    }
}
