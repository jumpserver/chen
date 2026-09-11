package org.jumpserver.chen.framework.datasource.edit.dialect;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.sql.SQLException;
import java.util.List;

public interface TableEditDialect {
    String quoteIdentifier(String identifier);

    String buildPreparedUpdateSql(String schema, String table, String sourceColumn, String pkColumn);

    String buildPreparedDeleteSql(String schema, String table, String pkColumn);

    String buildPreparedInsertSql(String schema, String table, List<String> sourceColumns);

    String renderLiteral(Object value, boolean isNull, Field field) throws SQLException;

    String buildAuditUpdateSql(
            String schema,
            String table,
            String sourceColumn,
            Field targetField,
            Object newValue,
            boolean newValueIsNull,
            String pkColumn,
            Field pkField,
            Object pkValue,
            boolean pkValueIsNull
    ) throws SQLException;

    String buildAuditDeleteSql(
            String schema,
            String table,
            String pkColumn,
            Field pkField,
            Object pkValue,
            boolean pkValueIsNull
    ) throws SQLException;

    String buildAuditInsertSql(
            String schema,
            String table,
            List<String> sourceColumns,
            List<Field> fields,
            List<Object> values,
            List<Boolean> valueIsNulls
    ) throws SQLException;
}
