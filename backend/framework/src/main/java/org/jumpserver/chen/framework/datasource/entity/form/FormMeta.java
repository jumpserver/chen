package org.jumpserver.chen.framework.datasource.entity.form;

import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;


@Data
@SuperBuilder
public class FormMeta {
    private String nodeKey;
    private String title;
    private String sqlTemplate;
    private String resource;
    private String method;
    private String width;
    private List<FormItem> formItems;
}
