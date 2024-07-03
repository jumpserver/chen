package org.jumpserver.chen.modules.oracle;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

public class OracleDatasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(OracleDatasource.class);
    }

    public OracleDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new OracleConnectionManager(dbConnectInfo, this);
        this.resourceBrowser = new OracleResourceBrowser(this.connectionManager);
        this.actionHandler = new OracleActionHandler();
    }

    @Override
    public String getName() {
        return "oracle";
    }


    @Override
    public DbType getDruidDbType() {
        return DbType.oracle;
    }
}
