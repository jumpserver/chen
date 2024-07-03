package org.jumpserver.chen.framework.session.controller.dialog;

import lombok.Data;

@Data
public class Button {
    private String label;
    private String event;
    private Runnable eventHandler;

    public Button(String label, String event, Runnable eventHandler) {
        this.label = label;
        this.event = event;
        this.eventHandler = eventHandler;
    }
}
