package org.jumpserver.chen.framework.datasource.entity.dialog.detail;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jumpserver.chen.framework.datasource.entity.dialog.Dialog;

import java.util.ArrayList;
import java.util.List;


@EqualsAndHashCode(callSuper = true)
@Data
public class DetailDialog extends Dialog {
    private String type = "detail";
    private List<DetailItem> items = new ArrayList<>();

    public DetailDialog(String nodeKey, String title) {
        super(nodeKey, title);
    }

    public DetailDialog addItem(DetailItem item) {
        this.items.add(item);
        return this;
    }
}
