package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

import java.sql.SQLException;

public class CommitOutcomeUnknownException extends TableEditException {
    public CommitOutcomeUnknownException(SQLException cause) {
        super(TableChangesSaveService.SAVE_CHANGES_COMMIT_OUTCOME_UNKNOWN, cause);
    }
}
