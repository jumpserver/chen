package org.jumpserver.chen.framework.console.dataview;

import lombok.Data;

@Data
public class UpdateDataView {
    private String id;
    private String title;
    private DataViewData data;

    public UpdateDataView(String title, DataViewData data) {
        this(title, title, data);
    }

    public UpdateDataView(String id, String title, DataViewData data) {
        this.id = id;
        this.title = title;
        this.data = data;
    }
}
