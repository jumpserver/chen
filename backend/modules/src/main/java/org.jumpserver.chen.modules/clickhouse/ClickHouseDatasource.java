package org.jumpserver.chen.modules.clickhouse;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

public class ClickHouseDatasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(ClickHouseDatasource.class);
    }

    public ClickHouseDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new ClickhouseConnectionManager(dbConnectInfo, this);
        this.resourceBrowser = new ClickhouseResourceBrowser(this.connectionManager);
        this.actionHandler = new ClickhouseActionHandler();
    }

    @Override
    public String getName() {
        return "clickhouse";
    }

    @Override
    public DbType getDruidDbType() {
        return DbType.clickhouse;
    }
}
