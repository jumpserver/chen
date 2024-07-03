package org.jumpserver.chen.framework.datasource.entity.form;

import lombok.Data;

import java.util.Map;

@Data
public class FormData {

    private String nodeKey;
    private String resource;
    private String method;
    private Map<String, Object> data;
}
