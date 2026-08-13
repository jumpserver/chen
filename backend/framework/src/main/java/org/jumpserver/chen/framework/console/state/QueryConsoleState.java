package org.jumpserver.chen.framework.console.state;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;


@EqualsAndHashCode(callSuper = true)
@Data
public class QueryConsoleState extends State {
    private volatile String currentContext;
    private boolean inQuery;
    private List<String> contexts;
    private int timeout;
    private boolean editorLoading;
    private boolean canCancel;
    private volatile String executionStatus;

    public QueryConsoleState(String title) {
        super(title);
        this.currentContext = "";
        this.inQuery = false;
        this.timeout = 30;
        this.editorLoading = false;
        this.canCancel = false;
        this.executionStatus = "";
    }

}
