package org.jumpserver.chen.web.config;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.web.service.SessionService;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import org.jumpserver.chen.web.service.impl.MockSessionService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@Data
@ConfigurationProperties(prefix = "mock")
public class MockConfig {
    private boolean enable;
    private DBConnectInfo mysql;
    private DBConnectInfo postgresql;
    private DBConnectInfo oracle;
    private DBConnectInfo sqlserver;
    private DBConnectInfo db2;
    private DBConnectInfo dameng;

    private DBConnectInfo clickhouse;


    public DBConnectInfo getMockDBInfo(String dbType) {
        return switch (dbType) {
            case "mysql" -> mysql;
            case "postgresql" -> postgresql;
            case "oracle" -> oracle;
            case "sqlserver" -> sqlserver;
            case "db2" -> db2;
            case "dameng" -> dameng;
            case "clickhouse" -> clickhouse;
            default -> throw new IllegalArgumentException("Unsupported db type: " + dbType);
        };
    }

    @Bean
    public SessionService sessionService() {
        if (enable) {
            return new MockSessionService();
        }
        return new JmsSessionService();
    }
}


