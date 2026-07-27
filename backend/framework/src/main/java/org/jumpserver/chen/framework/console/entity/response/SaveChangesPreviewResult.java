package org.jumpserver.chen.framework.console.entity.response;

import lombok.Data;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;

import java.util.ArrayList;
import java.util.List;

@Data
public class SaveChangesPreviewResult {
    private boolean success;
    private boolean allowed;
    private String reason;
    private Integer failedChangeIndex;
    private SaveChangesRequest.ChangeItem failedChange;
    private Object failedOperation;
    private String dataView;
    private String schema;
    private String table;
    private int changeCount;
    private int updateCount;
    private int insertCount;
    private int deleteCount;
    private List<PreviewItem> preparedStatements = new ArrayList<>();
    private String auditSql;

    @Data
    public static class PreviewItem {
        private String operation;
        private String sourceColumn;
        private String pkColumn;
        private String preparedSql;
        private List<Object> paramsPreview;
    }
}
