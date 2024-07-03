package org.jumpserver.chen.framework.datasource.entity.form;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
public class Input extends FormItem {
    private int maxLength;
    {
        this.type = "input";
    }

}
