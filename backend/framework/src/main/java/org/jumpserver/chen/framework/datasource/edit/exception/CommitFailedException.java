package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

import java.sql.SQLException;

public class CommitFailedException extends TableEditException {
    public CommitFailedException(SQLException cause) {
        super(TableChangesSaveService.SAVE_CHANGES_COMMIT_FAILED, cause);
    }
}
