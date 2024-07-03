package org.jumpserver.chen.framework.driver;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
public class DriverManager {

    private static final Map<String, List<DriverClassLoader>> driverClassLoaders = new HashMap<>();

    public static void registerDriver(String driverName, DriverClassLoader classLoader) {
        log.info("Register driver: {}}", driverName);
        var classLoaders = driverClassLoaders.get(driverName);
        if (classLoaders == null) {
            classLoaders = new ArrayList<>();
        }
        classLoaders.add(classLoader);
        driverClassLoaders.put(driverName, classLoaders);
    }

    public static List<DriverClassLoader> getDrivers(String dbType) {
        return driverClassLoaders.get(dbType);
    }

}
