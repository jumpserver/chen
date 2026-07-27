package org.jumpserver.chen.framework.datasource.edit;

import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.console.entity.response.SaveChangesPreviewResult;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

public class TableChangesPreviewService {
    public static final int MAX_CHANGES = TableChangesPlanBuilder.MAX_CHANGES;
    public static final String DATABASE_NOT_SUPPORTED_FOR_EDIT = TableChangesPlanBuilder.DATABASE_NOT_SUPPORTED_FOR_EDIT;
    public static final String DATA_VIEW_MISMATCH = TableChangesPlanBuilder.DATA_VIEW_MISMATCH;
    public static final String SCHEMA_TABLE_MISMATCH = TableChangesPlanBuilder.SCHEMA_TABLE_MISMATCH;
    public static final String EMPTY_CHANGES = TableChangesPlanBuilder.EMPTY_CHANGES;
    public static final String TOO_MANY_CHANGES = TableChangesPlanBuilder.TOO_MANY_CHANGES;
    public static final String NO_PRIMARY_KEY = TableChangesPlanBuilder.NO_PRIMARY_KEY;
    public static final String COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED = TableChangesPlanBuilder.COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED;
    public static final String PK_COLUMN_MISMATCH = TableChangesPlanBuilder.PK_COLUMN_MISMATCH;
    public static final String PRIMARY_KEY_VALUE_REQUIRED = TableChangesPlanBuilder.PRIMARY_KEY_VALUE_REQUIRED;
    public static final String PRIMARY_KEY_COLUMN_NOT_EDITABLE = TableChangesPlanBuilder.PRIMARY_KEY_COLUMN_NOT_EDITABLE;
    public static final String SOURCE_COLUMN_NOT_FOUND = TableChangesPlanBuilder.SOURCE_COLUMN_NOT_FOUND;
    public static final String SOURCE_COLUMN_AMBIGUOUS = TableChangesPlanBuilder.SOURCE_COLUMN_AMBIGUOUS;
    public static final String PRIMARY_KEY_SOURCE_COLUMN_MISSING = TableChangesPlanBuilder.PRIMARY_KEY_SOURCE_COLUMN_MISSING;
    public static final String SOURCE_SCHEMA_MISMATCH = TableChangesPlanBuilder.SOURCE_SCHEMA_MISMATCH;
    public static final String SOURCE_TABLE_MISMATCH = TableChangesPlanBuilder.SOURCE_TABLE_MISMATCH;
    public static final String SOURCE_COLUMN_NOT_EDITABLE = TableChangesPlanBuilder.SOURCE_COLUMN_NOT_EDITABLE;
    public static final String NO_OP_CHANGE = TableChangesPlanBuilder.NO_OP_CHANGE;
    public static final String TYPE_CONVERSION_FAILED = TableChangesPlanBuilder.TYPE_CONVERSION_FAILED;
    public static final String ROW_OPERATIONS_TABLE_BROWSE_ONLY = TableChangesPlanBuilder.ROW_OPERATIONS_TABLE_BROWSE_ONLY;
    public static final String INSERT_VALUES_REQUIRED = TableChangesPlanBuilder.INSERT_VALUES_REQUIRED;
    public static final String INSERT_COLUMN_NOT_WRITABLE = TableChangesPlanBuilder.INSERT_COLUMN_NOT_WRITABLE;

    private final TableChangesPlanBuilder planBuilder = new TableChangesPlanBuilder();

    public SaveChangesPreviewResult preview(TableEditContext context, String actionDataView, SaveChangesRequest request) {
        SaveChangesPreviewResult result = baseResult(context);
        TableChangesPlanBuildResult buildResult = this.planBuilder.build(context, actionDataView, request);
        if (!buildResult.isSuccess()) {
            result.setReason(buildResult.getReason());
            result.setFailedChangeIndex(buildResult.getFailedChangeIndex());
            result.setFailedChange(buildResult.getFailedChange());
            result.setFailedOperation(buildResult.getFailedOperation());
            return result;
        }

        TableChangesPlan plan = buildResult.getPlan();
        result.setSuccess(true);
        result.setAllowed(true);
        result.setChangeCount(plan.getChangeCount());
        result.setUpdateCount(plan.getUpdateCount());
        result.setInsertCount(plan.getInsertCount());
        result.setDeleteCount(plan.getDeleteCount());
        result.setAuditSql(plan.getAuditSql());
        for (PreparedTableChangeCommand command : plan.getCommands()) {
            SaveChangesPreviewResult.PreviewItem previewItem = new SaveChangesPreviewResult.PreviewItem();
            previewItem.setOperation(command.getOperation().name());
            previewItem.setSourceColumn(command.getSourceColumn());
            previewItem.setPkColumn(command.getPkColumn());
            previewItem.setPreparedSql(command.getPreparedSql());
            ArrayList<Object> paramsPreview = new ArrayList<>();
            for (PreparedTableChangeCommand.Parameter parameter : command.getParameters()) {
                paramsPreview.add(paramPreviewValue(parameter.getValue(), parameter.isValueIsNull()));
            }
            previewItem.setParamsPreview(paramsPreview);
            result.getPreparedStatements().add(previewItem);
        }
        return result;
    }

    private SaveChangesPreviewResult baseResult(TableEditContext context) {
        SaveChangesPreviewResult result = new SaveChangesPreviewResult();
        result.setSuccess(false);
        result.setAllowed(false);
        result.setDataView(context.getDataViewTitle());
        result.setSchema(context.getSchema());
        result.setTable(context.getTable());
        return result;
    }

    private Object paramPreviewValue(Object value, boolean isNull) {
        if (isNull) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toString();
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        return value;
    }
}
