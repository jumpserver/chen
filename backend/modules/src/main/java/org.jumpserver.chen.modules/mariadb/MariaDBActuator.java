package org.jumpserver.chen.modules.mariadb;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.modules.mysql.MysqlActuator;

import java.sql.Connection;

public class MariaDBActuator extends MysqlActuator {
    public MariaDBActuator(ConnectionManager connectionManager) {
        super(connectionManager);
    }
    public MariaDBActuator(MariaDBActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }
}
