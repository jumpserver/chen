package org.jumpserver.chen.framework.datasource.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class DBConnectInfo {
    private String dbType;
    private String host;
    private Integer port;
    private String user;
    private String password;
    private String db;
    private String proxyHost;
    private Integer proxyPort;

    private Map<String, Object> options = new HashMap<>();

    public String toDisplayJDBCUrl(String template) {
        return template.replace("${host}", this.host)
                .replace("${port}", this.port.toString())
                .replace("${db}", db);
    }

    public String toJDBCUrl(String template) {
        return this.toJDBCUrl(template, this.db);
    }

    public String toJDBCUrl(String template, String db) {
        var host = this.proxyHost == null ? this.host : this.proxyHost;
        var port = this.proxyPort == null ? this.port : this.proxyPort;


        return template.replace("${host}", host)
                .replace("${port}", port.toString())
                .replace("${db}", db);
    }
}
