package org.jumpserver.chen.modules.clickhouse;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;

public class ClickhouseResourceBrowser extends BaseResourceBrowser {
    public ClickhouseResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager);
    }
}
