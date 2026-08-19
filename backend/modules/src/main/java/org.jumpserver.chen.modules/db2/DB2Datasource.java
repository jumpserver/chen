package org.jumpserver.chen.modules.db2;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;

public class DB2Datasource extends BaseDatasource {

    static {
        DatasourceFactory.Register(DB2Datasource.class);
    }

    public DB2Datasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new DB2ConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new DB2MetadataProvider(this.connectionManager));
        this.resourceBrowser = new DB2ResourceBrowser(this.connectionManager);
        this.actionHandler = new DB2ActionHandler();
    }

    @Override
    public String getName() {
        return "db2";
    }


    @Override
    public DbType getDruidDbType() {
        return DbType.db2;
    }
}
