package org.jumpserver.chen.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
@ConfigurationProperties(prefix = "driver")
public class DriverConfig {
    private String driverPath = "drivers";

}
