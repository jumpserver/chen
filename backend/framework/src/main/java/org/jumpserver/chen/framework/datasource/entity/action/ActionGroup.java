package org.jumpserver.chen.framework.datasource.entity.action;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
public class ActionGroup extends Action {
    private List<Action> children;

    public ActionGroup withActions(List<Action> actions) {
        this.children = actions;
        return this;
    }
}
