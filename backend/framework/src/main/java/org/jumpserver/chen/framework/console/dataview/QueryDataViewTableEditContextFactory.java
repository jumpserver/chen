package org.jumpserver.chen.framework.console.dataview;

import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.edit.TableEditContext;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.List;
import java.util.Objects;

public class QueryDataViewTableEditContextFactory {
    public static final String FIELDS_REQUIRED = "FIELDS_REQUIRED";
    public static final String PRIMARY_KEY_REQUIRED = "PRIMARY_KEY_REQUIRED";
    public static final String PRIMARY_KEY_SOURCE_COLUMN_REQUIRED = "PRIMARY_KEY_SOURCE_COLUMN_REQUIRED";
    public static final String EDITABLE_FIELD_REQUIRED = "EDITABLE_FIELD_REQUIRED";
    public static final String EDITABLE_FIELD_SOURCE_REQUIRED = "EDITABLE_FIELD_SOURCE_REQUIRED";
    public static final String MIXED_SOURCE_TABLE_NOT_SUPPORTED = "MIXED_SOURCE_TABLE_NOT_SUPPORTED";
    public static final String MIXED_SOURCE_SCHEMA_NOT_SUPPORTED = "MIXED_SOURCE_SCHEMA_NOT_SUPPORTED";
    public static final String SOURCE_TABLE_REQUIRED = "SOURCE_TABLE_REQUIRED";

    public TableEditContext create(DataView dataView, DbType dbType) {
        List<Field> fields = getRequiredFields(dataView);

        Field primaryKey = getRequiredPrimaryKey(fields);
        validatePrimaryKey(primaryKey);

        List<Field> editableFields = getRequiredEditableFields(fields);
        validateEditableFields(editableFields);

        ensureSingleSource(fields);

        String sourceTable = resolveSourceTable(primaryKey, editableFields);
        String sourceSchema = resolveSourceSchema(primaryKey);

        ensureEditableFieldsMatchSource(editableFields, sourceSchema, sourceTable);

        TableEditContext context = new TableEditContext(dataView.getTitle(), sourceSchema, sourceTable, fields, dbType);
        context.setRowRefPrimaryKeys(dataView.getRowRefPrimaryKeys());
        return context;
    }

    private List<Field> getRequiredFields(DataView dataView) {
        List<Field> fields = dataView.getData().getFields();
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException(FIELDS_REQUIRED);
        }
        return fields;
    }

    private Field getRequiredPrimaryKey(List<Field> fields) {
        return fields.stream()
                .filter(Objects::nonNull)
                .filter(Field::isPrimaryKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(PRIMARY_KEY_REQUIRED));
    }

    private void validatePrimaryKey(Field primaryKey) {
        if (StringUtils.isBlank(primaryKey.getSourceColumn())) {
            throw new IllegalArgumentException(PRIMARY_KEY_SOURCE_COLUMN_REQUIRED);
        }
    }

    private List<Field> getRequiredEditableFields(List<Field> fields) {
        List<Field> editableFields = fields.stream()
                .filter(Objects::nonNull)
                .filter(Field::isEditable)
                .toList();

        if (editableFields.isEmpty()) {
            throw new IllegalArgumentException(EDITABLE_FIELD_REQUIRED);
        }

        return editableFields;
    }

    private void validateEditableFields(List<Field> editableFields) {
        for (Field field : editableFields) {
            if (StringUtils.isBlank(field.getSourceTable()) || StringUtils.isBlank(field.getSourceColumn())) {
                throw new IllegalArgumentException(EDITABLE_FIELD_SOURCE_REQUIRED);
            }
        }
    }

    private void ensureSingleSource(List<Field> fields) {
        ensureSingleSourceTable(fields);
        ensureSingleSourceSchema(fields);
    }

    private void ensureSingleSourceTable(List<Field> fields) {
        long sourceTableCount = fields.stream()
                .filter(Objects::nonNull)
                .map(Field::getSourceTable)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .count();

        if (sourceTableCount > 1) {
            throw new IllegalArgumentException(MIXED_SOURCE_TABLE_NOT_SUPPORTED);
        }
    }

    private void ensureSingleSourceSchema(List<Field> fields) {
        long sourceSchemaCount = fields.stream()
                .filter(Objects::nonNull)
                .map(Field::getSourceSchema)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .count();

        if (sourceSchemaCount > 1) {
            throw new IllegalArgumentException(MIXED_SOURCE_SCHEMA_NOT_SUPPORTED);
        }
    }

    private String resolveSourceTable(Field primaryKey, List<Field> editableFields) {
        String sourceTable = primaryKey.getSourceTable();

        if (StringUtils.isBlank(sourceTable)) {
            sourceTable = editableFields.get(0).getSourceTable();
        }

        if (StringUtils.isBlank(sourceTable)) {
            throw new IllegalArgumentException(SOURCE_TABLE_REQUIRED);
        }

        return sourceTable;
    }

    private String resolveSourceSchema(Field primaryKey) {
        // 保持原逻辑：sourceSchema 以主键字段为准，不从 editableFields fallback。
        return primaryKey.getSourceSchema();
    }

    private void ensureEditableFieldsMatchSource(
            List<Field> editableFields,
            String sourceSchema,
            String sourceTable
    ) {
        for (Field editableField : editableFields) {
            if (!StringUtils.equals(sourceTable, editableField.getSourceTable())) {
                throw new IllegalArgumentException(MIXED_SOURCE_TABLE_NOT_SUPPORTED);
            }

            if (!StringUtils.equals(sourceSchema, editableField.getSourceSchema())) {
                throw new IllegalArgumentException(MIXED_SOURCE_SCHEMA_NOT_SUPPORTED);
            }
        }
    }
}