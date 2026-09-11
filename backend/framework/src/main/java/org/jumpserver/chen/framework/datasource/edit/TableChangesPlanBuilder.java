package org.jumpserver.chen.framework.datasource.edit;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.datasource.edit.analyzer.EditabilityReason;
import org.jumpserver.chen.framework.datasource.edit.bind.TableEditTypeCodecs;
import org.jumpserver.chen.framework.datasource.edit.bind.TableEditValueConverter;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;
import org.jumpserver.chen.framework.datasource.edit.dialect.TableEditDialect;
import org.jumpserver.chen.framework.datasource.edit.dialect.TableEditDialects;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TableChangesPlanBuilder {
    public static final int MAX_CHANGES = 500;
    public static final String DATABASE_NOT_SUPPORTED_FOR_EDIT = "DATABASE_NOT_SUPPORTED_FOR_EDIT";
    public static final String DATA_VIEW_MISMATCH = "DATA_VIEW_MISMATCH";
    public static final String SCHEMA_TABLE_MISMATCH = "SCHEMA_TABLE_MISMATCH";
    public static final String EMPTY_CHANGES = "EMPTY_CHANGES";
    public static final String TOO_MANY_CHANGES = "TOO_MANY_CHANGES";
    public static final String NO_PRIMARY_KEY = "NO_PRIMARY_KEY";
    public static final String COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED = "COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED";
    public static final String PK_COLUMN_MISMATCH = "PK_COLUMN_MISMATCH";
    public static final String PRIMARY_KEY_VALUE_REQUIRED = "PRIMARY_KEY_VALUE_REQUIRED";
    public static final String PRIMARY_KEY_COLUMN_NOT_EDITABLE = "PRIMARY_KEY_COLUMN_NOT_EDITABLE";
    public static final String SOURCE_COLUMN_NOT_FOUND = "SOURCE_COLUMN_NOT_FOUND";
    public static final String SOURCE_COLUMN_AMBIGUOUS = "SOURCE_COLUMN_AMBIGUOUS";
    public static final String PRIMARY_KEY_SOURCE_COLUMN_MISSING = "PRIMARY_KEY_SOURCE_COLUMN_MISSING";
    public static final String SOURCE_SCHEMA_MISMATCH = "SOURCE_SCHEMA_MISMATCH";
    public static final String SOURCE_TABLE_MISMATCH = "SOURCE_TABLE_MISMATCH";
    public static final String SOURCE_COLUMN_NOT_EDITABLE = "SOURCE_COLUMN_NOT_EDITABLE";
    public static final String TYPE_CONVERSION_FAILED = "TYPE_CONVERSION_FAILED";
    public static final String ROW_OPERATIONS_TABLE_BROWSE_ONLY = "ROW_OPERATIONS_TABLE_BROWSE_ONLY";
    public static final String INSERT_VALUES_REQUIRED = "INSERT_VALUES_REQUIRED";
    public static final String INSERT_COLUMN_NOT_WRITABLE = "INSERT_COLUMN_NOT_WRITABLE";
    public static final String ROW_REF_REQUIRED = "ROW_REF_REQUIRED";
    public static final String ROW_REF_NOT_FOUND = "ROW_REF_NOT_FOUND";

    public TableChangesPlanBuildResult build(TableEditContext context, String actionDataView, SaveChangesRequest request) {
        TableEditDialect dialect = TableEditDialects.find(context.getDbType()).orElse(null);
        if (dialect == null) {
            return TableChangesPlanBuildResult.failure(DATABASE_NOT_SUPPORTED_FOR_EDIT, null, null);
        }
        if (!StringUtils.equals(actionDataView, context.getDataViewTitle())) {
            return TableChangesPlanBuildResult.failure(DATA_VIEW_MISMATCH, null, null);
        }
        if (request == null ||
                !StringUtils.equals(request.getSchema(), context.getSchema()) ||
                !StringUtils.equals(request.getTable(), context.getTable())) {
            return TableChangesPlanBuildResult.failure(SCHEMA_TABLE_MISMATCH, null, null);
        }

        int updateCount = size(request.getChanges());
        int insertCount = size(request.getInsertRows());
        int deleteCount = size(request.getDeleteRows());
        int totalCount = updateCount + insertCount + deleteCount;
        if (totalCount == 0) {
            return TableChangesPlanBuildResult.failure(EMPTY_CHANGES, null, null);
        }
        if (totalCount > MAX_CHANGES) {
            return TableChangesPlanBuildResult.failure(TOO_MANY_CHANGES, null, null);
        }
        if ((insertCount > 0 || deleteCount > 0) && !context.isTableBrowse()) {
            return TableChangesPlanBuildResult.failure(ROW_OPERATIONS_TABLE_BROWSE_ONLY, null, null);
        }
        if (insertCount > 0 && !TableInsertability.isInsertable(context.getFields())) {
            return TableChangesPlanBuildResult.failure(
                    INSERT_COLUMN_NOT_WRITABLE,
                    deleteCount + updateCount,
                    null,
                    request.getInsertRows().get(0)
            );
        }

        Field primaryKey = resolveSinglePrimaryKey(context.getFields());
        if (primaryKey == null) {
            return TableChangesPlanBuildResult.failure(NO_PRIMARY_KEY, null, null);
        }
        if (primaryKey == MultiplePrimaryKeys.FIELD) {
            return TableChangesPlanBuildResult.failure(COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED, null, null);
        }

        String pkColumn = primaryKey.getSourceColumn();
        if (StringUtils.isBlank(pkColumn)) {
            return TableChangesPlanBuildResult.failure(PRIMARY_KEY_SOURCE_COLUMN_MISSING, null, null);
        }
        if (!TableEditTypeCodecs.supports(primaryKey, context.getDbType())) {
            return TableChangesPlanBuildResult.failure(EditabilityReason.TYPE_NOT_SUPPORTED_FOR_EDIT, null, null);
        }

        TableChangesPlan plan = new TableChangesPlan();
        plan.setDataView(context.getDataViewTitle());
        plan.setSchema(context.getSchema());
        plan.setTable(context.getTable());
        plan.setChangeCount(totalCount);
        plan.setUpdateCount(updateCount);
        plan.setInsertCount(insertCount);
        plan.setDeleteCount(deleteCount);
        List<String> renderedSqlList = new ArrayList<>();

        int commandIndex = 0;
        BuildCommandsResult deleteResult = buildDeleteCommands(context, request, primaryKey, dialect, renderedSqlList, commandIndex, plan);
        if (!deleteResult.success()) {
            return deleteResult.failure();
        }
        commandIndex += deleteCount;

        BuildCommandsResult updateResult = buildUpdateCommands(context, request, primaryKey, dialect, renderedSqlList, commandIndex, plan);
        if (!updateResult.success()) {
            return updateResult.failure();
        }
        commandIndex += updateCount;

        BuildCommandsResult insertResult = buildInsertCommands(context, request, dialect, renderedSqlList, commandIndex, plan);
        if (!insertResult.success()) {
            return insertResult.failure();
        }

        plan.setAuditSqlList(List.copyOf(renderedSqlList));
        plan.setAuditSql(String.join("\n", renderedSqlList));
        return TableChangesPlanBuildResult.success(plan);
    }

    private BuildCommandsResult buildDeleteCommands(
            TableEditContext context,
            SaveChangesRequest request,
            Field primaryKey,
            TableEditDialect dialect,
            List<String> renderedSqlList,
            int commandIndexOffset,
            TableChangesPlan plan
    ) {
        if (request.getDeleteRows() == null) {
            return BuildCommandsResult.ok();
        }
        String pkColumn = primaryKey.getSourceColumn();
        for (int i = 0; i < request.getDeleteRows().size(); i++) {
            SaveChangesRequest.DeleteRow deleteRow = request.getDeleteRows().get(i);
            if (deleteRow == null) {
                return BuildCommandsResult.failure(SOURCE_COLUMN_NOT_FOUND, commandIndexOffset + i, null);
            }
            if (!StringUtils.equals(deleteRow.getPkColumn(), pkColumn)) {
                return BuildCommandsResult.failure(PK_COLUMN_MISMATCH, commandIndexOffset + i, deleteRow);
            }
            PrimaryKeyValue pkValue = resolvePrimaryKeyValue(
                    context, primaryKey, deleteRow.getRowRef(), deleteRow.getPkValue(), deleteRow.isPkValueIsNull()
            );
            if (pkValue.reason() != null) {
                return BuildCommandsResult.failure(pkValue.reason(), commandIndexOffset + i, deleteRow);
            }

            PreparedTableChangeCommand command = new PreparedTableChangeCommand();
            command.setOperation(PreparedTableChangeCommand.Operation.DELETE);
            command.setPkColumn(pkColumn);
            command.setDeleteRow(deleteRow);
            command.setPreparedSql(dialect.buildPreparedDeleteSql(context.getSchema(), context.getTable(), pkColumn));
            try {
                PreparedTableChangeCommand.Parameter pkParameter = convertedParameter(
                        "pkValue", pkColumn, primaryKey, pkValue.value(), pkValue.valueIsNull(), context.getDbType()
                );
                command.getParameters().add(pkParameter);
                renderedSqlList.add(dialect.buildAuditDeleteSql(
                        context.getSchema(),
                        context.getTable(),
                        pkColumn,
                        primaryKey,
                        pkParameter.getValue(),
                        pkValue.valueIsNull()
                ));
                plan.getCommands().add(command);
            } catch (SQLException e) {
                return BuildCommandsResult.failure(TYPE_CONVERSION_FAILED, commandIndexOffset + i, deleteRow);
            }
        }
        return BuildCommandsResult.ok();
    }

    private BuildCommandsResult buildUpdateCommands(
            TableEditContext context,
            SaveChangesRequest request,
            Field primaryKey,
            TableEditDialect dialect,
            List<String> renderedSqlList,
            int commandIndexOffset,
            TableChangesPlan plan
    ) {
        if (request.getChanges() == null) {
            return BuildCommandsResult.ok();
        }
        String pkColumn = primaryKey.getSourceColumn();
        for (int i = 0; i < request.getChanges().size(); i++) {
            SaveChangesRequest.ChangeItem change = request.getChanges().get(i);
            ChangeValidation validation = validateChange(context, change, primaryKey);
            if (validation.reason() != null) {
                return BuildCommandsResult.failure(validation.reason(), commandIndexOffset + i, change);
            }

            Field targetField = validation.field();
            String sourceColumn = targetField.getSourceColumn();
            PreparedTableChangeCommand command = new PreparedTableChangeCommand();
            command.setOperation(PreparedTableChangeCommand.Operation.UPDATE);
            command.setSourceColumn(sourceColumn);
            command.setPkColumn(pkColumn);
            command.setChange(change);
            command.setPreparedSql(dialect.buildPreparedUpdateSql(
                    context.getSchema(),
                    context.getTable(),
                    sourceColumn,
                    pkColumn
            ));
            try {
                PreparedTableChangeCommand.Parameter newParameter = convertedParameter(
                        "newValue", sourceColumn, targetField, change.getNewValue(), change.isNewValueIsNull(), context.getDbType()
                );
                PreparedTableChangeCommand.Parameter pkParameter = convertedParameter(
                        "pkValue", pkColumn, primaryKey, validation.pkValue(), validation.pkValueIsNull(), context.getDbType()
                );
                command.getParameters().add(newParameter);
                command.getParameters().add(pkParameter);
                renderedSqlList.add(dialect.buildAuditUpdateSql(
                        context.getSchema(),
                        context.getTable(),
                        sourceColumn,
                        targetField,
                        newParameter.getValue(),
                        change.isNewValueIsNull(),
                        pkColumn,
                        primaryKey,
                        pkParameter.getValue(),
                        validation.pkValueIsNull()
                ));
                plan.getCommands().add(command);
            } catch (SQLException e) {
                return BuildCommandsResult.failure(TYPE_CONVERSION_FAILED, commandIndexOffset + i, change);
            }
        }
        return BuildCommandsResult.ok();
    }

    private BuildCommandsResult buildInsertCommands(
            TableEditContext context,
            SaveChangesRequest request,
            TableEditDialect dialect,
            List<String> renderedSqlList,
            int commandIndexOffset,
            TableChangesPlan plan
    ) {
        if (request.getInsertRows() == null) {
            return BuildCommandsResult.ok();
        }
        for (int i = 0; i < request.getInsertRows().size(); i++) {
            SaveChangesRequest.InsertRow insertRow = request.getInsertRows().get(i);
            if (insertRow == null || insertRow.getValues() == null || insertRow.getValues().isEmpty()) {
                return BuildCommandsResult.failure(INSERT_VALUES_REQUIRED, commandIndexOffset + i, insertRow);
            }

            List<String> sourceColumns = new ArrayList<>();
            List<Field> fields = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            List<Boolean> valueIsNulls = new ArrayList<>();
            Map<String, SaveChangesRequest.CellValue> requestedValues = insertRow.getValues();
            for (Field field : context.getFields()) {
                if (field == null || StringUtils.isBlank(field.getSourceColumn())) {
                    continue;
                }
                if (!requestedValues.containsKey(field.getSourceColumn())) {
                    continue;
                }
                SaveChangesRequest.CellValue cellValue = requestedValues.get(field.getSourceColumn());
                if (cellValue == null) {
                    return BuildCommandsResult.failure(INSERT_VALUES_REQUIRED, commandIndexOffset + i, insertRow);
                }
                InsertValidation validation = validateInsertField(context, field);
                if (validation.reason() != null) {
                    return BuildCommandsResult.failure(validation.reason(), commandIndexOffset + i, insertRow);
                }
                sourceColumns.add(field.getSourceColumn());
                fields.add(field);
                try {
                    values.add(convertValue(cellValue.getValue(), cellValue.isValueIsNull(), field, context.getDbType()));
                } catch (SQLException e) {
                    return BuildCommandsResult.failure(TYPE_CONVERSION_FAILED, commandIndexOffset + i, insertRow);
                }
                valueIsNulls.add(cellValue.isValueIsNull());
            }

            if (sourceColumns.size() != requestedValues.size()) {
                return BuildCommandsResult.failure(SOURCE_COLUMN_NOT_FOUND, commandIndexOffset + i, insertRow);
            }
            if (sourceColumns.isEmpty()) {
                return BuildCommandsResult.failure(INSERT_VALUES_REQUIRED, commandIndexOffset + i, insertRow);
            }

            PreparedTableChangeCommand command = new PreparedTableChangeCommand();
            command.setOperation(PreparedTableChangeCommand.Operation.INSERT);
            command.setInsertRow(insertRow);
            command.setPreparedSql(dialect.buildPreparedInsertSql(context.getSchema(), context.getTable(), sourceColumns));
            for (int paramIndex = 0; paramIndex < sourceColumns.size(); paramIndex++) {
                command.getParameters().add(new PreparedTableChangeCommand.Parameter(
                        "insertValue",
                        sourceColumns.get(paramIndex),
                        fields.get(paramIndex),
                        values.get(paramIndex),
                        valueIsNulls.get(paramIndex),
                        context.getDbType()
                ));
            }
            plan.getCommands().add(command);
            try {
                renderedSqlList.add(dialect.buildAuditInsertSql(
                        context.getSchema(),
                        context.getTable(),
                        sourceColumns,
                        fields,
                        values,
                        valueIsNulls
                ));
            } catch (SQLException e) {
                return BuildCommandsResult.failure(TYPE_CONVERSION_FAILED, commandIndexOffset + i, insertRow);
            }
        }
        return BuildCommandsResult.ok();
    }

    private ChangeValidation validateChange(TableEditContext context, SaveChangesRequest.ChangeItem change, Field primaryKey) {
        if (change == null) {
            return ChangeValidation.failure(SOURCE_COLUMN_NOT_FOUND);
        }
        if (!StringUtils.equals(change.getPkColumn(), primaryKey.getSourceColumn())) {
            return ChangeValidation.failure(PK_COLUMN_MISMATCH);
        }
        PrimaryKeyValue pkValue = resolvePrimaryKeyValue(
                context, primaryKey, change.getRowRef(), change.getPkValue(), change.isPkValueIsNull()
        );
        if (pkValue.reason() != null) {
            return ChangeValidation.failure(pkValue.reason());
        }
        if (StringUtils.equals(change.getSourceColumn(), primaryKey.getSourceColumn())) {
            return ChangeValidation.failure(PRIMARY_KEY_COLUMN_NOT_EDITABLE);
        }

        Field field = resolveUniqueSourceField(context.getFields(), change.getSourceColumn());
        if (field == null) {
            return ChangeValidation.failure(SOURCE_COLUMN_NOT_FOUND);
        }
        if (field == MultiplePrimaryKeys.FIELD) {
            return ChangeValidation.failure(SOURCE_COLUMN_AMBIGUOUS);
        }

        String sourceReason = validateSourceField(context, field);
        if (sourceReason != null) {
            return ChangeValidation.failure(sourceReason);
        }
        if (field.isAutoIncrement() || field.isReadOnly() || field.isGenerated()) {
            return ChangeValidation.failure(SOURCE_COLUMN_NOT_EDITABLE);
        }
        if (field.isPrimaryKey()) {
            return ChangeValidation.failure(PRIMARY_KEY_COLUMN_NOT_EDITABLE);
        }
        if (!field.isEditable()) {
            return ChangeValidation.failure(StringUtils.defaultIfBlank(field.getEditReason(), SOURCE_COLUMN_NOT_EDITABLE));
        }
        if (!TableEditTypeCodecs.supports(field, context.getDbType())) {
            return ChangeValidation.failure(EditabilityReason.TYPE_NOT_SUPPORTED_FOR_EDIT);
        }
        return ChangeValidation.success(field, pkValue.value(), pkValue.valueIsNull());
    }

    private InsertValidation validateInsertField(TableEditContext context, Field field) {
        String sourceReason = validateSourceField(context, field);
        if (sourceReason != null) {
            return InsertValidation.failure(sourceReason);
        }
        if (!field.isInsertable()) {
            return InsertValidation.failure(StringUtils.defaultIfBlank(field.getInsertReason(), INSERT_COLUMN_NOT_WRITABLE));
        }
        if (field.isAutoIncrement() || field.isReadOnly() || field.isGenerated()) {
            return InsertValidation.failure(INSERT_COLUMN_NOT_WRITABLE);
        }
        if (!TableEditTypeCodecs.supports(field, context.getDbType())) {
            return InsertValidation.failure(EditabilityReason.TYPE_NOT_SUPPORTED_FOR_EDIT);
        }
        return InsertValidation.success();
    }

    private PrimaryKeyValue resolvePrimaryKeyValue(
            TableEditContext context,
            Field primaryKey,
            String rowRef,
            Object pkValue,
            boolean pkValueIsNull
    ) {
        if (primaryKey.isMasked()) {
            if (StringUtils.isBlank(rowRef)) {
                return PrimaryKeyValue.failure(ROW_REF_REQUIRED);
            }
            Map<String, Object> rowRefs = context.getRowRefPrimaryKeys();
            if (rowRefs == null || !rowRefs.containsKey(rowRef)) {
                return PrimaryKeyValue.failure(ROW_REF_NOT_FOUND);
            }
            Object realPkValue = rowRefs.get(rowRef);
            if (realPkValue == null) {
                return PrimaryKeyValue.failure(PRIMARY_KEY_VALUE_REQUIRED);
            }
            return PrimaryKeyValue.success(realPkValue, false);
        }
        if (pkValueIsNull || pkValue == null) {
            return PrimaryKeyValue.failure(PRIMARY_KEY_VALUE_REQUIRED);
        }
        return PrimaryKeyValue.success(pkValue, false);
    }

    private PreparedTableChangeCommand.Parameter convertedParameter(
            String name,
            String column,
            Field field,
            Object value,
            boolean valueIsNull,
            com.alibaba.druid.DbType dbType
    ) throws SQLException {
        return new PreparedTableChangeCommand.Parameter(
                name,
                column,
                field,
                convertValue(value, valueIsNull, field, dbType),
                valueIsNull,
                field != null ? field.getJdbcType() : null,
                dbType
        );
    }

    private Object convertValue(Object value, boolean valueIsNull, Field field, com.alibaba.druid.DbType dbType) throws SQLException {
        if (valueIsNull || value == null) {
            return null;
        }
        return TableEditValueConverter.coerce(value, field, dbType);
    }

    private String validateSourceField(TableEditContext context, Field field) {
        if (StringUtils.isNotBlank(field.getSourceSchema()) && !StringUtils.equals(field.getSourceSchema(), context.getSchema())) {
            return SOURCE_SCHEMA_MISMATCH;
        }
        if (StringUtils.isNotBlank(field.getSourceTable()) && !StringUtils.equals(field.getSourceTable(), context.getTable())) {
            return SOURCE_TABLE_MISMATCH;
        }
        return null;
    }

    private Field resolveSinglePrimaryKey(List<Field> fields) {
        if (fields == null) {
            return null;
        }
        List<Field> primaryKeys = fields.stream()
                .filter(Objects::nonNull)
                .filter(Field::isPrimaryKey)
                .toList();
        if (primaryKeys.isEmpty()) {
            return null;
        }
        if (primaryKeys.size() > 1) {
            return MultiplePrimaryKeys.FIELD;
        }
        return primaryKeys.get(0);
    }

    private Field resolveUniqueSourceField(List<Field> fields, String sourceColumn) {
        if (fields == null) {
            return null;
        }
        List<Field> matchedFields = fields.stream()
                .filter(Objects::nonNull)
                .filter(field -> StringUtils.equals(field.getSourceColumn(), sourceColumn))
                .toList();
        if (matchedFields.isEmpty()) {
            return null;
        }
        if (matchedFields.size() > 1) {
            return MultiplePrimaryKeys.FIELD;
        }
        return matchedFields.get(0);
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private record ChangeValidation(String reason, Field field, Object pkValue, boolean pkValueIsNull) {
        static ChangeValidation success(Field field, Object pkValue, boolean pkValueIsNull) {
            return new ChangeValidation(null, field, pkValue, pkValueIsNull);
        }

        static ChangeValidation failure(String reason) {
            return new ChangeValidation(reason, null, null, false);
        }
    }

    private record PrimaryKeyValue(String reason, Object value, boolean valueIsNull) {
        static PrimaryKeyValue success(Object value, boolean valueIsNull) {
            return new PrimaryKeyValue(null, value, valueIsNull);
        }

        static PrimaryKeyValue failure(String reason) {
            return new PrimaryKeyValue(reason, null, false);
        }
    }

    private record InsertValidation(String reason) {
        static InsertValidation success() {
            return new InsertValidation(null);
        }

        static InsertValidation failure(String reason) {
            return new InsertValidation(reason);
        }
    }

    private record BuildCommandsResult(boolean successful, TableChangesPlanBuildResult failureResult) {
        boolean success() {
            return successful;
        }

        TableChangesPlanBuildResult failure() {
            return failureResult;
        }

        static BuildCommandsResult ok() {
            return new BuildCommandsResult(true, null);
        }

        static BuildCommandsResult failure(String reason, Integer failedChangeIndex, Object failedOperation) {
            SaveChangesRequest.ChangeItem failedChange = failedOperation instanceof SaveChangesRequest.ChangeItem change ? change : null;
            return new BuildCommandsResult(false,
                    TableChangesPlanBuildResult.failure(reason, failedChangeIndex, failedChange, failedOperation));
        }
    }

    private static final class MultiplePrimaryKeys {
        private static final Field FIELD = new Field();
    }
}
