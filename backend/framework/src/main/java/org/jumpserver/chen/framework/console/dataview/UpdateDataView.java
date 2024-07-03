package org.jumpserver.chen.framework.console.dataview;

import lombok.Data;

@Data
public class UpdateDataView {
    private String title;
    private DataViewData data;

    public UpdateDataView(String title, DataViewData data) {
        this.title = title;
        this.data = data;
    }
}
