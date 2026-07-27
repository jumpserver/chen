package org.jumpserver.chen.framework.console.transaction;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
final class Db2TransactionStateProbe implements TransactionStateProbe {
    private static final String DRIVER_CONNECTION = "com.ibm.db2.jcc.DB2Connection";

    @Override
    public QueryTransactionState inspect(Connection connection) {
        try {
            Class<?> driverClass = Class.forName(
                    DRIVER_CONNECTION,
                    false,
                    connection.getClass().getClassLoader()
            );
            Object driverConnection = driverClass.isInstance(connection)
                    ? connection
                    : connection.unwrap(driverClass);
            boolean inUnitOfWork = (boolean) driverClass
                    .getMethod("isInDB2UnitOfWork")
                    .invoke(driverConnection);
            return map(inUnitOfWork, connection.getAutoCommit());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | SQLException | RuntimeException e) {
            log.debug("inspect DB2 transaction state failed", e);
            throw new TransactionStateProbeException(e);
        }
    }

    static QueryTransactionState map(boolean inUnitOfWork, boolean autoCommit) {
        if (inUnitOfWork) {
            return QueryTransactionState.TRANSACTION_ACTIVE;
        }
        return autoCommit
                ? QueryTransactionState.AUTO_COMMIT
                : QueryTransactionState.MANUAL_COMMIT_IDLE;
    }
}
