package org.jumpserver.chen.framework.datasource.entity.form;


import lombok.Data;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Data
public class FormItem {

    protected String type;
    protected String name;

    protected String label;
    protected String placeholder;
    protected boolean required;
}

