package org.jumpserver.chen.framework.datasource.entity.form;


import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class Select extends FormItem {
    {
        this.type = "select";
    }

    private List<Option> options;

}
