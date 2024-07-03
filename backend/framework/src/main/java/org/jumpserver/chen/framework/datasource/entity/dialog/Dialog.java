package org.jumpserver.chen.framework.datasource.entity.dialog;

import lombok.Data;

@Data
public abstract class Dialog {
    private String nodeKey;
    private String title;
    private String width = "30%";

    public Dialog() {
    }

    public Dialog(String nodeKey, String title) {
        this.nodeKey = nodeKey;
        this.title = title;

    }
}
