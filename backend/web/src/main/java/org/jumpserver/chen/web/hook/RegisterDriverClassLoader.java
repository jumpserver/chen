package org.jumpserver.chen.web.hook;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.framework.driver.DriverManager;
import org.jumpserver.chen.web.config.DriverConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;

@Slf4j
@Component
public class RegisterDriverClassLoader {
    @Autowired
    private DriverConfig driverConfig;

    @PostConstruct
    public void registerDriverClassLoader() {
        var driverPath = driverConfig.getDriverPath();

        File driverDir = new File(driverPath);
        if (!driverDir.exists()) {
            throw new RuntimeException("Driver path not exists: " + driverPath);
        }

        File[] files = driverDir.listFiles();
        if (files == null) {
            throw new RuntimeException("Driver path is empty: " + driverPath);
        }

        for (File file : files) {
            if (!file.isDirectory()) {
                continue;
            }

            var dbType = file.getName();

            File[] jars = file.listFiles();

            for (File jar : jars != null ? jars : new File[0]) {
                if (!jar.getName().endsWith(".jar")) {
                    continue;
                }
                try {
                    var url = jar.toURI().toURL();
                    var classLoader = new DriverClassLoader(jar.getName(), url);
                    DriverManager.registerDriver(dbType, classLoader);
                } catch (Exception e) {
                    log.error("Register driver error: {}", e.getMessage(), e);
                }
            }
        }
    }
}
