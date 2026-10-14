package org.jumpserver.chen.modules.oceanbase;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;
import org.jumpserver.chen.modules.mysql.MysqlActionHandler;
import org.jumpserver.chen.modules.mysql.MysqlExecutionPlanDialect;
import org.jumpserver.chen.modules.mysql.MysqlMetadataProvider;
import org.jumpserver.chen.modules.mysql.MysqlResourceBrowser;
import org.jumpserver.chen.modules.oracle.OracleActionHandler;
import org.jumpserver.chen.modules.oracle.OracleExecutionPlanDialect;
import org.jumpserver.chen.modules.oracle.OracleMetadataProvider;
import org.jumpserver.chen.modules.oracle.OracleResourceBrowser;

public class OceanBaseDatasource extends BaseDatasource {
    private final boolean oracleMode;
    private final ExecutionPlanDialect executionPlanDialect;

    static {
        DatasourceFactory.Register(OceanBaseDatasource.class);
    }

    public OceanBaseDatasource(DBConnectInfo dbConnectInfo) {
        oracleMode = "oracle".equals(dbConnectInfo.getTenantMode());
        connectionManager = new OceanBaseConnectionManager(dbConnectInfo, this);
        if (oracleMode) {
            metadataCatalog = new MetadataCatalog(connectionManager, new OracleMetadataProvider(connectionManager));
            resourceBrowser = new OracleResourceBrowser(connectionManager);
            actionHandler = new OracleActionHandler();
            executionPlanDialect = new OracleExecutionPlanDialect();
        } else {
            metadataCatalog = new MetadataCatalog(connectionManager, new MysqlMetadataProvider(connectionManager));
            resourceBrowser = new MysqlResourceBrowser(connectionManager);
            actionHandler = new MysqlActionHandler();
            executionPlanDialect = new MysqlExecutionPlanDialect();
        }
    }

    @Override
    public String getName() {
        return "oceanbase";
    }

    @Override
    public DbType getDruidDbType() {
        return oracleMode ? DbType.oracle : DbType.mysql;
    }

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return executionPlanDialect;
    }
}
