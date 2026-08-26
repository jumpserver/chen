package org.jumpserver.chen.framework.datasource.edit.dialect;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.sql.SQLException;

public interface TableEditDialect {
    String quoteIdentifier(String identifier);

    String buildPreparedUpdateSql(String schema, String table, String sourceColumn, String pkColumn);

    String buildPreparedDeleteSql(String schema, String table, String pkColumn);

    String buildPreparedInsertSql(String schema, String table, java.util.List<String> sourceColumns);

    int oldValueParameterCount();

    // For preview/audit SQL only. Real writes must use PreparedStatement binding.
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
            boolean pkValueIsNull,
            Object oldValue,
            boolean oldValueIsNull
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
            java.util.List<String> sourceColumns,
            java.util.List<Field> fields,
            java.util.List<Object> values,
            java.util.List<Boolean> valueIsNulls
    ) throws SQLException;
}
