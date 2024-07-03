package org.jumpserver.chen.framework.datasource.sql;


import lombok.Data;

import java.util.List;

@Data
public class SQLQueryParams {
    private List<String> orderFields;
    private String order = "asc";

    private int offset;
    private int limit;

    private int timeout = -1;
}
