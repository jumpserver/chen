package org.jumpserver.chen.modules.mariadb;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.modules.mysql.MysqlResourceBrowser;


public class MariaDBResourceBrowser extends MysqlResourceBrowser {

    public MariaDBResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager);
    }
}
