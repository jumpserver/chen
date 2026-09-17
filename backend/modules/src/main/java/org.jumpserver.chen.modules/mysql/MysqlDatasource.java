package org.jumpserver.chen.modules.mysql;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;

public class MysqlDatasource extends BaseDatasource {
    private final ExecutionPlanDialect executionPlanDialect = new MysqlExecutionPlanDialect();

    static {
        DatasourceFactory.Register(MysqlDatasource.class);
    }

    public MysqlDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new MysqlConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new MysqlMetadataProvider(this.connectionManager));
        this.resourceBrowser = new MysqlResourceBrowser(this.connectionManager);
        this.actionHandler = new MysqlActionHandler();
    }

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return this.executionPlanDialect;
    }

    @Override
    public String getName() {
        return "mysql";
    }


    @Override
    public DbType getDruidDbType() {
        return DbType.mysql;
    }
}
