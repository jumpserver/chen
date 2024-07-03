package org.jumpserver.chen.framework.session.controller.message;

import lombok.Data;

@Data
public class Message {
    private final MessageLevel level;
    private final String message;
    private int duration = 3;

    public Message(MessageLevel level, String message) {
        this.level = level;
        this.message = message;
    }
}
