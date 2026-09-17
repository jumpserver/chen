package org.jumpserver.chen.framework.datasource.edit;

import lombok.Data;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;

@Data
public class TableChangesPlanBuildResult {
    private boolean success;
    private String reason;
    private Integer failedChangeIndex;
    private SaveChangesRequest.ChangeItem failedChange;
    private Object failedOperation;
    private TableChangesPlan plan;

    public static TableChangesPlanBuildResult success(TableChangesPlan plan) {
        TableChangesPlanBuildResult result = new TableChangesPlanBuildResult();
        result.setSuccess(true);
        result.setPlan(plan);
        return result;
    }

    public static TableChangesPlanBuildResult failure(String reason, Integer failedChangeIndex, SaveChangesRequest.ChangeItem failedChange) {
        return failure(reason, failedChangeIndex, failedChange, failedChange);
    }

    public static TableChangesPlanBuildResult failure(String reason, Integer failedChangeIndex, SaveChangesRequest.ChangeItem failedChange, Object failedOperation) {
        TableChangesPlanBuildResult result = new TableChangesPlanBuildResult();
        result.setSuccess(false);
        result.setReason(reason);
        result.setFailedChangeIndex(failedChangeIndex);
        result.setFailedChange(failedChange);
        result.setFailedOperation(failedOperation);
        return result;
    }
}
