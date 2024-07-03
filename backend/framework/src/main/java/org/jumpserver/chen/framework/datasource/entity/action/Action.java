package org.jumpserver.chen.framework.datasource.entity.action;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class Action {
    private String label;
    private String key;
    private String icon;
    private boolean disabled;
    private boolean divided;
}
