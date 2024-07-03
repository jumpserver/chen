package org.jumpserver.chen.framework.datasource;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class DatasourceFactory {

    private static final Map<String, Class<? extends Datasource>> DATASOURCE_MAP = new ConcurrentHashMap<>();

    public static Datasource fromConnectInfo(DBConnectInfo info) {
        var dbType = info.getDbType();
        var datasource = DATASOURCE_MAP.get(info.getDbType());
        if (datasource == null) {
            throw new RuntimeException("Unsupported dbType: " + dbType);
        }
        try {
            return datasource.getConstructor(DBConnectInfo.class).newInstance(info);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static void Register(Class<? extends Datasource> datasource) {
        var dbType = datasource.getPackage().getName().split("\\.")[4];
        log.info("Register datasource for dbType: {}", datasource.getName());
        DATASOURCE_MAP.put(dbType, datasource);
    }
}
