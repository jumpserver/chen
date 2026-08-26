package org.jumpserver.chen.framework.console.entity.request;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SaveChangesRequest {
    private String schema;
    private String table;
    private List<ChangeItem> changes;
    private List<InsertRow> insertRows;
    private List<DeleteRow> deleteRows;

    @Data
    public static class ChangeItem {
        private String pkColumn;
        private Object pkValue;
        private boolean pkValueIsNull;
        private String sourceColumn;
        private Object oldValue;
        private boolean oldValueIsNull;
        private Object newValue;
        private boolean newValueIsNull;
    }

    @Data
    public static class InsertRow {
        private Map<String, CellValue> values;
    }

    @Data
    public static class DeleteRow {
        private String pkColumn;
        private Object pkValue;
        private boolean pkValueIsNull;
    }

    @Data
    public static class CellValue {
        private Object value;
        private boolean valueIsNull;
    }
}
