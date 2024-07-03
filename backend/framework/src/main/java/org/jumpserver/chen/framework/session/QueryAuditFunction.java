package org.jumpserver.chen.framework.session;


import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.SQLException;

@FunctionalInterface
public interface QueryAuditFunction {
    SQLQueryResult run() throws SQLException;

}
