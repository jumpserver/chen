package org.jumpserver.chen.framework.datasource.edit.bind;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.sql.SQLException;

public final class TableEditValueConverter {
    private TableEditValueConverter() {
    }

    public static Object coerce(Object value, Field field) throws SQLException {
        return TableEditTypeCodecs.coerce(value, field);
    }

    public static Object coerce(Object value, Field field, DbType dbType) throws SQLException {
        return TableEditTypeCodecs.coerce(value, field, dbType);
    }
}
