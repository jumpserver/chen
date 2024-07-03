package org.jumpserver.chen.framework.datasource.exception;

import com.alibaba.druid.sql.parser.ParserException;


public class SQLDangerException extends ParserException {
    public SQLDangerException(String sql, String reason) {
        super(String.format("SQL '%s' is dangerous, reason: %s", sql, reason));
    }
}
