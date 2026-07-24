package org.jumpserver.chen.modules.mariadb;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.modules.mysql.MysqlActuator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MariaDBActuator extends MysqlActuator {
    public MariaDBActuator(ConnectionManager connectionManager) {
        super(connectionManager);
    }
    public MariaDBActuator(MariaDBActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }

    @Override
    protected Object normalizeJdbcValue(ResultSet resultSet, int columnIndex) throws SQLException {
        var columnTypeName = resultSet.getMetaData().getColumnTypeName(columnIndex);
        if ("JSON".equalsIgnoreCase(columnTypeName) || "MYSQL_JSON".equalsIgnoreCase(columnTypeName)) {
            return resultSet.getString(columnIndex);
        }
        return super.normalizeJdbcValue(resultSet, columnIndex);
    }
}
