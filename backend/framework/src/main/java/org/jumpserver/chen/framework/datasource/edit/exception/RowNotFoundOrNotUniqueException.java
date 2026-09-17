package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

import java.sql.SQLException;

public class RowNotFoundOrNotUniqueException extends SQLException {
    private final int changeIndex;

    public RowNotFoundOrNotUniqueException(int changeIndex) {
        super(TableChangesSaveService.ROW_NOT_FOUND_OR_NOT_UNIQUE);
        this.changeIndex = changeIndex;
    }

    public int getChangeIndex() {
        return changeIndex;
    }
}
