package org.jumpserver.chen.modules.db2;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.sql.SQLException;

public class DB2ConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:db2://${host}:${port}/${db}";
    private String jdbcUrl;

    public DB2ConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new DB2Actuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "com.ibm.db2.jcc.DB2Driver";
    }


    @Override
    public void ping() throws SQLException {
        var url = this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate);
        this.ping(url);
        this.jdbcUrl = url;
    }

    @Override
    public String getVersion() throws SQLException {
        var result = this.sqlActuator.execute(SQL.of("SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO"));
        return (String) result.getData().get(0).get(0);
    }


    @Override
    public String getJDBCUrl() {
        return this.jdbcUrl;
    }

    @Override
    public String getDisplayJDBCUrl() {
        return this.getConnectInfo().toDisplayJDBCUrl(jdbcUrlTemplate);
    }


    @Override
    public String getJDBCUrl(String database) {
        return this.jdbcUrl;
    }
}
