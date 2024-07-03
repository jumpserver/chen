package org.jumpserver.chen.framework.ws.io;

import lombok.Data;

@Data
public class Packet {


    public static final String TYPE_UPDATE_STATE = "update_state";
    public static final String TYPE_CONNECT = "connect";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_GLOBAL_MESSAGE = "global_message";
    public static final String TYPE_LOG = "log";
    public static final String TYPE_QUERY_CONSOLE_ACTION = "query_console_action";
    public static final String TYPE_DATA_VIEW_ACTION = "data_view_action";
    public static final String TYPE_UPDATE_DATA_VIEW = "update_data_view";
    public static final String TYPE_NEW_DATA_VIEW = "new_data_view";


    private String type;
    private Object data;

}


