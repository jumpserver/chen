package org.jumpserver.chen.framework.console.entity.request;

import lombok.Data;

@Data
public class Connect {

    public static final String CONSOLE_TYPE_QUERY = "query";
    public static final String CONSOLE_TYPE_DATA_VIEW = "data_view";

    private String nodeKey;
    private String type;
}
