package org.jumpserver.chen.modules.mariadb;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mysql.MysqlActionHandler;

public class MariaDBDatasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(MariaDBDatasource.class);
    }

    public MariaDBDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new MariaDBConnectionManager(dbConnectInfo, this);
        this.resourceBrowser = new MariaDBResourceBrowser(this.connectionManager);
        this.actionHandler = new MysqlActionHandler();
    }

    @Override
    public String getName() {
        return "mariadb";
    }

    @Override
    public DbType getDruidDbType() {
        return DbType.mysql;
    }
}
