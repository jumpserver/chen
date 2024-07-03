package org.jumpserver.chen.web.exception;

public class ChenException extends RuntimeException {

    public ChenException(String message) {
        super(message);
    }

    public ChenException(String message, Throwable cause) {
        super(message + ":" + cause.getMessage(), cause);
    }

}
