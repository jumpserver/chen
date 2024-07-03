package org.jumpserver.chen.framework.datasource.entity.action;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor(staticName = "of")
public class EventEmitter {
    private String event;
    private Object data;

}
