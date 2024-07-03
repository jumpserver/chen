package org.jumpserver.chen.framework.datasource.entity.form;


import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
public class CheckBox extends FormItem {
    private String label;
    private Object value;

    {
        this.type = "checkbox";
    }
}
