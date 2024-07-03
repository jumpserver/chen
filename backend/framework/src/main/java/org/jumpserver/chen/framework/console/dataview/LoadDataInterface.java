package org.jumpserver.chen.framework.console.dataview;

import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.SQLException;

@FunctionalInterface
public interface LoadDataInterface {
    SQLQueryResult loadData(SQLQueryParams params) throws SQLException;
}
