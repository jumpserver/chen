package org.jumpserver.chen.framework.datasource.sql;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class SQL {


    private String sql;
    private List<Object> parameters = new ArrayList<>();

    public static SQL bound(String sql, Object... parameters) {
        SQL query = new SQL(sql);
        query.parameters = java.util.Arrays.asList(parameters.clone());
        return query;
    }

    public SQL(String sql) {
        this.sql = sql;
    }

    public static SQL of(String sql, Map<String, Object> params) {
        List<String> paramNames = new ArrayList<>();
        Matcher matcher = Pattern.compile(":(\\w+)").matcher(sql);
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }
        for (String paramName : paramNames) {
            Object paramValue = params.get(paramName);
            if (paramValue == null) {
                paramValue = "";
            }
            sql = sql.replace(String.format(":%s", paramName), paramValue.toString());
        }
        return new SQL(sql);
    }

    public static SQL of(String sql, Object... params) {
        StringBuilder sb = new StringBuilder();
        int lastPos = 0;
        for (Object param : params) {
            int pos = sql.indexOf("?", lastPos);
            if (pos == -1) break;
            sb.append(sql, lastPos, pos);
            sb.append(param.toString()); // 这里直接 append，完全不会触发 $ 报错
            lastPos = pos + 1;
        }
        sb.append(sql.substring(lastPos));
        return new SQL(sb.toString());
    }

    public static SQL of(String sql) {
        return new SQL(sql);
    }
}
