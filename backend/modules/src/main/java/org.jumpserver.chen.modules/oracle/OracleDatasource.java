package org.jumpserver.chen.modules.oracle;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;

public class OracleDatasource extends BaseDatasource {
    private final ExecutionPlanDialect executionPlanDialect = new OracleExecutionPlanDialect();

    static {
        DatasourceFactory.Register(OracleDatasource.class);
    }

    public OracleDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new OracleConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new OracleMetadataProvider(this.connectionManager));
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

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return this.executionPlanDialect;
    }
}
