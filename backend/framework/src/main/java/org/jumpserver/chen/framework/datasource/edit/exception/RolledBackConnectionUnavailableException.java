package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

import java.sql.SQLException;

public class RolledBackConnectionUnavailableException extends TableEditException {
    public RolledBackConnectionUnavailableException(SQLException cause, Throwable primaryException) {
        super(TableChangesSaveService.SAVE_CHANGES_ROLLED_BACK_CONNECTION_UNAVAILABLE, cause);
        this.addSuppressed(primaryException);
    }
}
