package org.jumpserver.chen.framework.datasource.edit.exception;

import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;

public class OptimisticLockConflictException extends TableEditException {
    private final int changeIndex;

    public OptimisticLockConflictException(int changeIndex) {
        super(TableChangesSaveService.OPTIMISTIC_LOCK_CONFLICT);
        this.changeIndex = changeIndex;
    }

    public int getChangeIndex() {
        return changeIndex;
    }
}
