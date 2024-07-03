package org.jumpserver.chen.framework.session.controller.dialog;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class Dialog {
    private String title = "提示";
    private String width = "30%";
    private String body;
    private String bodyType = "text";
    private boolean showClose;
    private int delayCloseSeconds = -1;

    private Map<String, Runnable> events = new ConcurrentHashMap<>();
    private List<Button> buttons = new ArrayList<>();

    public Dialog(String title) {
        this.title = title;
    }

    public void addButton(Button button) {
        this.buttons.add(button);
        this.addEvent(button.getEvent(), button.getEventHandler());
    }

    public void addEvent(String event, Runnable runnable) {
        this.events.put(event, runnable);
    }

    public Runnable getEvent(String event) {
        if (this.events.containsKey(event)) {
            return this.events.remove(event);
        }
        return null;
    }
}
