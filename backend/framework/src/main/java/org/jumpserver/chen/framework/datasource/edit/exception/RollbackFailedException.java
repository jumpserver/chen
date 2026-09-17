package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

import java.sql.SQLException;

public class RollbackFailedException extends TableEditException {
    public RollbackFailedException(SQLException cause, Throwable primaryException) {
        super(TableChangesSaveService.SAVE_CHANGES_ROLLBACK_FAILED, cause);
        this.addSuppressed(primaryException);
    }
}
