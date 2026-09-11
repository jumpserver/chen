package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.edit.bind.TableEditTypeCodecs;
import org.jumpserver.chen.framework.datasource.sql.SQLIdentifier;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

abstract class AbstractTableEditDialect implements TableEditDialect {
    private final DbType dbType;

    AbstractTableEditDialect(DbType dbType) {
        this.dbType = dbType;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return SQLIdentifier.quote(this.dbType, identifier);
    }

    @Override
    public String buildPreparedUpdateSql(String schema, String table, String sourceColumn, String pkColumn) {
        return "UPDATE " + this.qualifiedTable(schema, table) + "\n" +
                "SET " + this.quoteIdentifier(sourceColumn) + " = ?\n" +
                "WHERE " + this.quoteIdentifier(pkColumn) + " = ?";
    }

    @Override
    public String buildPreparedDeleteSql(String schema, String table, String pkColumn) {
        return "DELETE FROM " + this.qualifiedTable(schema, table) + "\n" +
                "WHERE " + this.quoteIdentifier(pkColumn) + " = ?";
    }

    @Override
    public String buildPreparedInsertSql(String schema, String table, List<String> sourceColumns) {
        String columns = sourceColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String placeholders = sourceColumns.stream()
                .map(column -> "?")
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + this.qualifiedTable(schema, table) + " (" + columns + ")\n" +
                "VALUES (" + placeholders + ")";
    }

    @Override
    public String renderLiteral(Object value, boolean isNull, Field field) throws SQLException {
        return TableEditTypeCodecs.renderLiteral(value, isNull, field, this.dbType);
    }

    @Override
    public String buildAuditUpdateSql(
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
    ) throws SQLException {
        if (targetField != null && StringUtils.isNotBlank(targetField.getSourceColumn()) &&
                !StringUtils.equals(targetField.getSourceColumn(), sourceColumn)) {
            throw new SQLException("sourceColumn does not match targetField.sourceColumn");
        }
        return "UPDATE " + this.qualifiedTable(schema, table) + "\n" +
                "SET " + this.quoteIdentifier(sourceColumn) + " = " +
                this.renderLiteral(newValue, newValueIsNull, targetField) + "\n" +
                "WHERE " + this.quoteIdentifier(pkColumn) + " = " +
                this.renderLiteral(pkValue, pkValueIsNull, pkField) + ";";
    }

    @Override
    public String buildAuditDeleteSql(
            String schema,
            String table,
            String pkColumn,
            Field pkField,
            Object pkValue,
            boolean pkValueIsNull
    ) throws SQLException {
        return "DELETE FROM " + this.qualifiedTable(schema, table) + "\n" +
                "WHERE " + this.quoteIdentifier(pkColumn) + " = " +
                this.renderLiteral(pkValue, pkValueIsNull, pkField) + ";";
    }

    @Override
    public String buildAuditInsertSql(
            String schema,
            String table,
            List<String> sourceColumns,
            List<Field> fields,
            List<Object> values,
            List<Boolean> valueIsNulls
    ) throws SQLException {
        String columns = sourceColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        StringBuilder renderedValues = new StringBuilder();
        for (int i = 0; i < sourceColumns.size(); i++) {
            if (i > 0) {
                renderedValues.append(", ");
            }
            renderedValues.append(this.renderLiteral(values.get(i), valueIsNulls.get(i), fields.get(i)));
        }
        return "INSERT INTO " + this.qualifiedTable(schema, table) + " (" + columns + ")\n" +
                "VALUES (" + renderedValues + ");";
    }

    protected String qualifiedTable(String schema, String table) {
        if (StringUtils.isBlank(schema)) {
            return this.quoteIdentifier(table);
        }
        return this.quoteIdentifier(schema) + "." + this.quoteIdentifier(table);
    }
}
