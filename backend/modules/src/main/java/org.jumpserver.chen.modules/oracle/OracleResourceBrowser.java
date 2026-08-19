package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;

public class OracleResourceBrowser extends BaseResourceBrowser {
    public OracleResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new OracleSQLHintsHandler(connectionManager));
    }
}
