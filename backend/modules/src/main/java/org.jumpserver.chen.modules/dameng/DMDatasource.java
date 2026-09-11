package org.jumpserver.chen.modules.dameng;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;

public class DMDatasource extends BaseDatasource {
    private final ExecutionPlanDialect executionPlanDialect = new DmExecutionPlanDialect();

    static {
        DatasourceFactory.Register(DMDatasource.class);
    }

    public DMDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new DMConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new DmMetadataProvider(this.connectionManager));
        this.resourceBrowser = new DMResourceBrowser(this.connectionManager);
        this.actionHandler = new DMActionHandler();
    }

    @Override
    public String getName() {
        return "dameng";
    }

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return this.executionPlanDialect;
    }


    @Override
    public DbType getDruidDbType() {
        return DbType.dm;
    }
}
