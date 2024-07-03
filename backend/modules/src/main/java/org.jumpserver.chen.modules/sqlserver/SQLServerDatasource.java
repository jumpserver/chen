package org.jumpserver.chen.modules.sqlserver;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

public class SQLServerDatasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(SQLServerDatasource.class);
    }

    public SQLServerDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new SQLServerConnectionManager(dbConnectInfo,this);
        this.resourceBrowser = new SQLServerResourceBrowser(this.connectionManager);
        this.actionHandler = new SQLServerActionHandler();
    }

    @Override
    public String getName() {
        return "sqlserver";
    }


    @Override
    public DbType getDruidDbType() {
        return DbType.sqlserver;
    }


}
