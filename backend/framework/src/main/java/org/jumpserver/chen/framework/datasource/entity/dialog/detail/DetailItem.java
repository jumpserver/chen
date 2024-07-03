package org.jumpserver.chen.framework.datasource.entity.dialog.detail;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class DetailItem {
    private String name;
    private String label;
    private String type = "text";
    private String value;
}
