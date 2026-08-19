package org.jumpserver.chen.modules.mysql;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;

public class MysqlResourceBrowser extends BaseResourceBrowser {
    public MysqlResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new MysqlSQLHintsHandler(connectionManager));
    }
}
