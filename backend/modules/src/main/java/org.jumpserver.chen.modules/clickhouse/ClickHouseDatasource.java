package org.jumpserver.chen.modules.clickhouse;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;

public class ClickHouseDatasource extends BaseDatasource {
    private final ExecutionPlanDialect executionPlanDialect = new ClickHouseExecutionPlanDialect();

    static {
        DatasourceFactory.Register(ClickHouseDatasource.class);
    }

    public ClickHouseDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new ClickhouseConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new ClickhouseMetadataProvider(this.connectionManager));
        this.resourceBrowser = new ClickhouseResourceBrowser(this.connectionManager);
        this.actionHandler = new ClickhouseActionHandler();
    }

    @Override
    public String getName() {
        return "clickhouse";
    }

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return this.executionPlanDialect;
    }

    @Override
    public DbType getDruidDbType() {
        return DbType.clickhouse;
    }
}
