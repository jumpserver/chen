package org.jumpserver.chen.modules.mariadb;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.base.BaseDatasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.plan.ExecutionPlanDialect;
import org.jumpserver.chen.modules.mysql.MysqlMetadataProvider;
import org.jumpserver.chen.modules.mysql.MysqlActionHandler;

public class MariaDBDatasource extends BaseDatasource {
    private final ExecutionPlanDialect executionPlanDialect = new MariaDBExecutionPlanDialect();

    static {
        DatasourceFactory.Register(MariaDBDatasource.class);
    }

    public MariaDBDatasource(DBConnectInfo dbConnectInfo) {
        this.connectionManager = new MariaDBConnectionManager(dbConnectInfo, this);
        this.metadataCatalog = new MetadataCatalog(this.connectionManager, new MysqlMetadataProvider(this.connectionManager));
        this.resourceBrowser = new MariaDBResourceBrowser(this.connectionManager);
        this.actionHandler = new MysqlActionHandler();
    }

    @Override
    public ExecutionPlanDialect getExecutionPlanDialect() {
        return this.executionPlanDialect;
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
