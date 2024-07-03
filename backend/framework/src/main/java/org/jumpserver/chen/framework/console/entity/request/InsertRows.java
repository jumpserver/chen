package org.jumpserver.chen.framework.console.entity.request;

import java.util.ArrayList;
import java.util.List;

public class InsertRows extends AbstractTableAction {
    private List<List<String>> data = new ArrayList<>();

    public List<List<String>> getData() {
        return data;
    }

    public void setData(List<List<String>> data) {
        this.data = data;
    }
}
