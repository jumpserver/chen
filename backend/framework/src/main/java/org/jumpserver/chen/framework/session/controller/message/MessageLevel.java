package org.jumpserver.chen.framework.session.controller.message;

public enum MessageLevel {
    SUCCESS("success"), WARNING("warning"), ERROR("error"), INFO("info");
    private String value;

    MessageLevel(String value) {
        this.value = value;
    }
}
