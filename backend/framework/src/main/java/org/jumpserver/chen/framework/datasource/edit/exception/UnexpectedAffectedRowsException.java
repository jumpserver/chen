package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

public class UnexpectedAffectedRowsException extends TableEditException {
    private final int changeIndex;
    private final int affectedRows;

    public UnexpectedAffectedRowsException(int changeIndex, int affectedRows) {
        super(TableChangesSaveService.AFFECTED_ROWS_UNEXPECTED);
        this.changeIndex = changeIndex;
        this.affectedRows = affectedRows;
    }

    public int getChangeIndex() {
        return changeIndex;
    }

    public int getAffectedRows() {
        return affectedRows;
    }
}
