package org.jumpserver.chen.framework.session.global;

import lombok.Data;

@Data
public class GlobalEvent {
    private String title;
    private String event;
    private Object data;

    public GlobalEvent(String title, String event) {
        this.title = title;
        this.event = event;
    }
}
