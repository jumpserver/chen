package org.jumpserver.chen.framework.console.entity.response;


import lombok.Data;

@Data
public class Config {
    private String schema;
    private int maxResultRows;
    private int timeout;
    private boolean autoCommit;

    public static Config getDefault() {
        var cfg = new Config();
        cfg.setSchema("");
        cfg.setMaxResultRows(50);
        cfg.setTimeout(30);
        cfg.setAutoCommit(true);
        return cfg;
    }

}
