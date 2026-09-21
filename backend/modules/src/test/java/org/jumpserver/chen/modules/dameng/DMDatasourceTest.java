package org.jumpserver.chen.modules.dameng;

import com.alibaba.druid.DbType;
import org.junit.Test;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DMDatasourceTest {
    @Test
    public void factoryCreatesDamengDatasourceWithTheBundledJdbcDriver() {
        var connectInfo = new DBConnectInfo();
        connectInfo.setDbType("dameng");
        connectInfo.setHost("dm.example");
        connectInfo.setPort(5236);
        connectInfo.setDb("APP");

        var datasource = DatasourceFactory.fromConnectInfo(connectInfo);

        assertTrue(datasource instanceof DMDatasource);
        assertEquals("dameng", datasource.getName());
        assertEquals(DbType.dm, datasource.getDruidDbType());
        assertEquals("dm.jdbc.driver.DmDriver", datasource.getConnectionManager().getDriverClassName());
        assertEquals("jdbc:dm://dm.example:5236/APP", datasource.getConnectionManager().getDisplayJDBCUrl());
    }
}
