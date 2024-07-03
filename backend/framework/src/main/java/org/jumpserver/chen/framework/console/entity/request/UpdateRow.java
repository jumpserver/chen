package org.jumpserver.chen.framework.console.entity.request;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateRow extends AbstractTableAction {
    private List<String> data;
    private Map<Integer, String> values = new HashMap();


    public Map<Integer, String> getValues() {
        return values;
    }

    public void setValues(Map<Integer, String> values) {
        this.values = values;
    }

    public List<String> getData() {
        return data;
    }

    public void setData(List<String> data) {
        this.data = data;
    }
}
