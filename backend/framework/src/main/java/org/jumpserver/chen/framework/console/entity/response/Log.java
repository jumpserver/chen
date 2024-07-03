package org.jumpserver.chen.framework.console.entity.response;
import lombok.Data;

@Data
public class Log {
    private int level;
    private String message;
    private String timestamp;

    public Log(int level, String message) {
        this.level = level;
        this.message = message;
        this.timestamp = String.valueOf(System.currentTimeMillis());
    }
}

