package org.jumpserver.chen.modules.oceanbase;

import com.alibaba.druid.DbType;
import org.junit.Test;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.framework.driver.DriverManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class OceanBaseDatasourceTest {
    @Test
    public void selectsTheOceanBaseDriverAndTenantDialect() {
        for (var mode : new String[]{"mysql", "oracle"}) {
            var info = new DBConnectInfo();
            info.setDbType("oceanbase");
            info.setTenantMode(mode);
            info.setHost("ob.example");
            info.setPort(2881);
            info.setDb("");

            var datasource = DatasourceFactory.fromConnectInfo(info);

            assertTrue(datasource instanceof OceanBaseDatasource);
            assertEquals("com.oceanbase.jdbc.Driver", datasource.getConnectionManager().getDriverClassName());
            assertEquals("oracle".equals(mode) ? DbType.oracle : DbType.mysql, datasource.getDruidDbType());
            assertEquals("jdbc:oceanbase://ob.example:2881/?useUnicode=true&characterEncoding=UTF-8",
                    datasource.getConnectionManager().getDisplayJDBCUrl());
        }
    }

    @Test
    public void connectsToBothTenantModesWithTheOfficialConnector() throws Exception {
        var host = System.getenv("OB_TEST_HOST");
        var password = System.getenv("OB_TEST_PASSWORD");
        var jar = Path.of("../..", "drivers/oceanbase/oceanbase-client-2.4.18.jar").toAbsolutePath().normalize();
        assumeTrue(host != null && password != null && Files.isRegularFile(jar));

        DriverManager.registerDriver("oceanbase", new DriverClassLoader(
                jar.getFileName().toString(), jar.toUri().toURL()));
        for (var mode : new String[]{"mysql", "oracle"}) {
            var username = System.getenv("oracle".equals(mode) ? "OB_TEST_ORACLE_USER" : "OB_TEST_MYSQL_USER");
            assumeTrue(username != null);
            var info = new DBConnectInfo();
            info.setDbType("oceanbase");
            info.setTenantMode(mode);
            info.setHost(host);
            info.setPort(Integer.parseInt(System.getenv().getOrDefault("OB_TEST_PORT", "2881")));
            info.setDb("");
            info.setUser(username);
            info.setPassword(password);
            var datasource = DatasourceFactory.fromConnectInfo(info);
            try {
                datasource.getConnectionManager().ping();
                try (var connection = datasource.getConnectionManager().getConnection();
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("oracle".equals(mode) ?
                             "SELECT 1 FROM DUAL" : "SELECT 1")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (var connection = datasource.getConnectionManager().getConnection();
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("oracle".equals(mode) ?
                             "SELECT BANNER FROM V$VERSION" : "SELECT VERSION()")) {
                    assertTrue(result.next());
                    assertTrue(result.getString(1).contains("OceanBase"));
                }
            } finally {
                datasource.close();
            }
        }
    }
}
