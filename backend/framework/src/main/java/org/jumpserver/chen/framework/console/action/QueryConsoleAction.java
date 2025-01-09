package org.jumpserver.chen.framework.console.action;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryConsoleAction extends Action {
    public static final String ACTION_RUN_SQL = "run_sql";
    public static final String ACTION_RUN_SQL_CHUNK = "run_sql_chunk";
    public static final String ACTION_RUN_SQL_COMPLETE = "run_sql_complete";
    public static final String ACTION_RUN_SQL_FILE = "run_sql_file";
    public static final String ACTION_CANCEL = "cancel";
    public static final String ACTION_CHANGE_CURRENT_CONTEXT = "change_current_context";


}
