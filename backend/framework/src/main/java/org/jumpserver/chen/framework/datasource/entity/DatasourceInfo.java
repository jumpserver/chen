package org.jumpserver.chen.framework.datasource.entity;

import lombok.Data;

@Data
public class DatasourceInfo {
    private String name;
    private String dbType;
    private String jdbcUrl;
    private String dbUser;
    private String version;
    private String driverClassName;
    private String driverVersion;
}
