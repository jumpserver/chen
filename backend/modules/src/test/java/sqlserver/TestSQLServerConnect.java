package sqlserver;

import com.alibaba.druid.pool.DruidDataSource;

import java.sql.SQLException;

public class TestSQLServerConnect {

    public static void main(String[] args) {
//        DruidDataSource ds = new DruidDataSource();
//        ds.setUrl("jdbc:sqlserver://172.16.10.180:1433;DatabaseName=jumpserver;trustServerCertificate=true;");
//        ds.setUsername("Administrator");
//        ds.setPassword("Calong@2015");
//        ds.setKeepAlive(false);
//        ds.setFailFast(true);
//        try {
//            ds.init();
//            var conn = ds.getConnection();
//        } catch (SQLException e) {
//            throw new RuntimeException(e);
//        }

        DruidDataSource ds = new DruidDataSource();
        ds.setUrl("jdbc:db2://localhost:50000/test");
        ds.setUsername("db2inst1");
        ds.setPassword("db2");
        ds.setDriverClassName("com.ibm.db2.jcc.DB2Driver");
        ds.setTestWhileIdle(true);
        ds.setValidationQuery("SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO");
        try {
            ds.init();
            var conn = ds.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
