package org.jumpserver.chen.modules.dameng;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;

public class DMResourceBrowser extends BaseResourceBrowser {
    public DMResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new DMSQLHintsHandler(connectionManager));
    }
}
