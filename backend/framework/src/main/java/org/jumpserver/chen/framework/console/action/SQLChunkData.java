package org.jumpserver.chen.framework.console.action;

import lombok.Data;

@Data
public class SQLChunkData {
    private String chunk;
    private Integer index;
    private Integer total;
}
