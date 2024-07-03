package org.jumpserver.chen.framework.console.entity.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class Message extends SQLResult {
    private String type;
    private String message;
    private String title;

    private static final String MESSAGE_ERROR = "error";
    private static final String MESSAGE_SUCCESS = "success";
    private static final String MESSAGE_INFO = "info";


    static public Message success(String title, String message, Object... args) {
        return create(MESSAGE_SUCCESS, title, message, args);
    }

    static public Message error(String title, String message, Object... args) {
        return create(MESSAGE_ERROR, title, message, args);
    }

    static public Message info(String title, String message, Object... args) {
        return create(MESSAGE_INFO, title, message, args);
    }

    static public Message create(String type, String title, String message, Object... args) {
        Message msg = new Message();
        msg.setType(type);
        msg.setTitle(title);
        msg.setMessage(String.format(message, args));
        return msg;
    }
}
