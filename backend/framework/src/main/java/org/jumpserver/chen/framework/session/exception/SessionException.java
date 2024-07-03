package org.jumpserver.chen.framework.session.exception;

public class SessionException extends RuntimeException {
    public SessionException(String username, String message) {
        super(String.format("Session %s exception: %s", username, message));
    }

}
