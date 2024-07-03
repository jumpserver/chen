package org.jumpserver.chen.framework.console.action;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataViewAction extends Action {

    public static final String ACTION_FIRST_PAGE = "first_page";
    public static final String ACTION_PREV_PAGE = "prev_page";
    public static final String ACTION_NEXT_PAGE = "next_page";
    public static final String ACTION_LAST_PAGE = "last_page";
    public static final String ACTION_REFRESH = "refresh";
    public static final String ACTION_TOGGLE_PINNED = "toggle_pinned";
    public static final String ACTION_CHANGE_LIMIT = "change_limit";
    public static final String ACTION_EXPORT = "export";

}
