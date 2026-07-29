package org.jumpserver.chen.framework.datasource.edit.analyzer;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectGroupByClause;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.edit.TableChangesPlanBuilder;
import org.jumpserver.chen.framework.datasource.edit.bind.TableEditTypeCodecs;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.edit.pk.PrimaryKeyResolution;
import org.jumpserver.chen.framework.datasource.edit.pk.PrimaryKeyResolver;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

@Slf4j
public class QueryResultEditabilityAnalyzer {
    private final DbType dbType;
    private final PrimaryKeyResolver primaryKeyResolver;

    public QueryResultEditabilityAnalyzer(DbType dbType, PrimaryKeyResolver primaryKeyResolver) {
        this.dbType = dbType;
        this.primaryKeyResolver = primaryKeyResolver;
    }

    public void analyze(String sourceSQL, List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }

        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sourceSQL, this.dbType);
            if (statements.size() != 1) {
                this.markAllReadOnly(fields, EditabilityReason.MULTIPLE_STATEMENTS_NOT_SUPPORTED);
                return;
            }
            if (!(statements.get(0) instanceof SQLSelectStatement selectStatement)) {
                this.markAllReadOnly(fields, EditabilityReason.NOT_SELECT);
                return;
            }
            this.analyzeSelect(selectStatement, fields);
        } catch (Exception e) {
            log.debug("analyze query result editability failed", e);
            this.markAllReadOnly(fields, EditabilityReason.UNKNOWN_COLUMN_SOURCE);
        }
    }

    private void analyzeSelect(SQLSelectStatement selectStatement, List<Field> fields) {
        SQLSelectQueryBlock queryBlock = this.resolveSupportedQueryBlock(selectStatement, fields);
        if (queryBlock == null) {
            return;
        }

        SourceTable source = this.resolveSourceTable(queryBlock, fields);

        if (!this.mapSelectItems(
                queryBlock.getSelectList(),
                fields,
                source.schema(),
                source.table(),
                source.alias()
        )) {
            return;
        }

        this.applyPrimaryKeyEditability(fields, source.schema(), source.table());
    }

    private SQLSelectQueryBlock resolveSupportedQueryBlock(SQLSelectStatement selectStatement, List<Field> fields) {
        var select = selectStatement.getSelect();
        if (select.getWithSubQuery() != null) {
            this.markAllReadOnly(fields, EditabilityReason.CTE_NOT_SUPPORTED);
            return null;
        }
        if (select.getQuery() instanceof SQLUnionQuery) {
            this.markAllReadOnly(fields, EditabilityReason.SET_OPERATION_NOT_SUPPORTED);
            return null;
        }
        if (!(select.getQuery() instanceof SQLSelectQueryBlock queryBlock)) {
            this.markAllReadOnly(fields, EditabilityReason.QUERY_BLOCK_NOT_SUPPORTED);
            return null;
        }

        if (!this.validateQueryBlock(queryBlock, fields)) {
            return null;
        }
        return queryBlock;
    }

    private SourceTable resolveSourceTable(SQLSelectQueryBlock queryBlock, List<Field> fields) {
        var tableSource = (SQLExprTableSource) queryBlock.getFrom();
        String rawSchema = tableSource.getSchema();
        String rawTable = tableSource.getTableName();
        String schema = normalizeIdentifier(rawSchema);
        String table = normalizeIdentifier(rawTable);
        String tableAlias = normalizeIdentifier(tableSource.getAlias());
        schema = this.canonicalizeSourceSchema(schema, fields, isQuotedIdentifier(rawSchema));
        table = this.canonicalizeSourceTable(table, fields, isQuotedIdentifier(rawTable));

        return new SourceTable(schema, table, tableAlias);
    }

    private boolean validateQueryBlock(SQLSelectQueryBlock queryBlock, List<Field> fields) {
        if (queryBlock.isDistinct()) {
            this.markAllReadOnly(fields, EditabilityReason.DISTINCT_NOT_SUPPORTED);
            return false;
        }

        SQLSelectGroupByClause groupBy = queryBlock.getGroupBy();
        if (groupBy != null && !groupBy.getItems().isEmpty()) {
            this.markAllReadOnly(fields, EditabilityReason.GROUP_BY_NOT_SUPPORTED);
            return false;
        }
        if (groupBy != null && groupBy.getHaving() != null) {
            this.markAllReadOnly(fields, EditabilityReason.HAVING_NOT_SUPPORTED);
            return false;
        }

        SQLTableSource from = queryBlock.getFrom();
        if (from instanceof SQLJoinTableSource) {
            this.markAllReadOnly(fields, EditabilityReason.JOIN_NOT_SUPPORTED);
            return false;
        }
        if (from instanceof SQLSubqueryTableSource) {
            this.markAllReadOnly(fields, EditabilityReason.SUBQUERY_NOT_SUPPORTED);
            return false;
        }
        if (!(from instanceof SQLExprTableSource)) {
            this.markAllReadOnly(fields, EditabilityReason.TABLE_SOURCE_NOT_SUPPORTED);
            return false;
        }
        if (!containsAllColumn(queryBlock.getSelectList()) && queryBlock.getSelectList().size() != fields.size()) {
            this.markAllReadOnly(fields, EditabilityReason.SELECT_LIST_MISMATCH);
            return false;
        }
        return true;
    }

    private boolean mapSelectItems(List<SQLSelectItem> selectItems,
                                   List<Field> fields,
                                   String schema,
                                   String table,
                                   String tableAlias) {
        if (containsAllColumn(selectItems)) {
            return this.mapAllColumns(selectItems, fields, schema, table, tableAlias);
        }

        for (int i = 0; i < selectItems.size(); i++) {
            var field = fields.get(i);
            var column = resolveColumn(selectItems.get(i).getExpr(), table, tableAlias);
            if (column.reason() != null) {
                this.markAllReadOnly(fields, column.reason());
                return false;
            }

            field.setSourceSchema(schema);
            field.setSourceTable(table);
            field.setSourceColumn(this.canonicalizeSourceColumn(field, column.name(), column.quoted()));
        }
        return true;
    }

    private boolean mapAllColumns(List<SQLSelectItem> selectItems,
                                  List<Field> fields,
                                  String schema,
                                  String table,
                                  String tableAlias) {
        if (selectItems.size() != 1) {
            this.markAllReadOnly(fields, EditabilityReason.ALL_COLUMNS_NOT_SUPPORTED);
            return false;
        }

        var star = selectItems.get(0).getExpr();
        String owner = starOwner(star);
        if (owner == null) {
            this.markAllReadOnly(fields, EditabilityReason.ALL_COLUMNS_NOT_SUPPORTED);
            return false;
        }
        if (StringUtils.isNotBlank(owner) && !matchesTableOwner(owner, table, tableAlias)) {
            this.markAllReadOnly(fields, EditabilityReason.COLUMN_OWNER_NOT_SUPPORTED);
            return false;
        }

        for (Field field : fields) {
            String sourceColumn = this.canonicalizeSourceColumn(field, normalizeIdentifier(field.getColumnName()), false);
            if (StringUtils.isBlank(sourceColumn)) {
                this.markAllReadOnly(fields, EditabilityReason.UNKNOWN_COLUMN_SOURCE);
                return false;
            }
            field.setSourceSchema(schema);
            field.setSourceTable(table);
            field.setSourceColumn(sourceColumn);
        }
        return true;
    }

    private ColumnResolution resolveColumn(SQLExpr expr, String table, String tableAlias) {
        if (expr instanceof SQLIdentifierExpr identifierExpr) {
            String rawName = identifierExpr.getName();
            return ColumnResolution.column(normalizeIdentifier(rawName), isQuotedIdentifier(rawName));
        }
        if (expr instanceof SQLPropertyExpr propertyExpr) {
            String owner = normalizeIdentifier(propertyExpr.getOwnerName());
            if (!matchesTableOwner(owner, table, tableAlias)) {
                return ColumnResolution.reason(EditabilityReason.COLUMN_OWNER_NOT_SUPPORTED);
            }
            String rawName = propertyExpr.getName();
            return ColumnResolution.column(normalizeIdentifier(rawName), isQuotedIdentifier(rawName));
        }
        if (expr instanceof SQLAggregateExpr || expr instanceof SQLMethodInvokeExpr) {
            return ColumnResolution.reason(EditabilityReason.AGGREGATE_OR_FUNCTION_COLUMN);
        }
        return ColumnResolution.reason(EditabilityReason.EXPRESSION_COLUMN);
    }

    private static boolean containsAllColumn(List<SQLSelectItem> selectItems) {
        return selectItems.stream()
                .map(SQLSelectItem::getExpr)
                .anyMatch(QueryResultEditabilityAnalyzer::isAllColumn);
    }

    private static boolean isAllColumn(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr) {
            return true;
        }
        if (expr instanceof SQLPropertyExpr propertyExpr) {
            return Objects.equals(normalizeIdentifier(propertyExpr.getName()), "*");
        }
        return false;
    }

    private static String starOwner(SQLExpr expr) {
        if (expr instanceof SQLAllColumnExpr allColumnExpr) {
            SQLExpr owner = allColumnExpr.getOwner();
            return owner == null ? "" : normalizeIdentifier(owner.toString());
        }
        if (expr instanceof SQLPropertyExpr propertyExpr && Objects.equals(normalizeIdentifier(propertyExpr.getName()), "*")) {
            return normalizeIdentifier(propertyExpr.getOwnerName());
        }
        return null;
    }

    private void applyPrimaryKeyEditability(List<Field> fields, String schema, String table) {
        PrimaryKeyResolution resolution;
        try {
            resolution = this.primaryKeyResolver.resolvePrimaryKeys(schema, table);
        } catch (SQLException e) {
            log.debug("resolve primary key failed for {}.{}", schema, table, e);
            this.markAllReadOnly(fields, EditabilityReason.PRIMARY_KEY_RESOLUTION_FAILED);
            return;
        }

        if (resolution == null) {
            this.markAllReadOnly(fields, EditabilityReason.PRIMARY_KEY_RESOLUTION_NOT_IMPLEMENTED);
            return;
        }
        if (StringUtils.isNotBlank(resolution.getReadOnlyReason())) {
            this.markAllReadOnly(fields, resolution.getReadOnlyReason());
            return;
        }

        List<String> primaryKeys = resolution.getPrimaryKeys();
        if (primaryKeys.isEmpty()) {
            this.markAllReadOnly(fields, EditabilityReason.NO_PRIMARY_KEY);
            return;
        }
        if (primaryKeys.size() > 1) {
            this.markAllReadOnly(fields, EditabilityReason.COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED);
            return;
        }

        String primaryKey = normalizeIdentifier(primaryKeys.get(0));
        var primaryKeyField = fields.stream()
                .filter(field -> identifierEquals(field.getSourceColumn(), primaryKey))
                .findFirst();
        if (primaryKeyField.isEmpty()) {
            this.markAllReadOnly(fields, EditabilityReason.PRIMARY_KEY_NOT_IN_RESULT);
            return;
        }

        fields.forEach(field -> {
            this.applyColumnEditability(field, primaryKey);
            this.applyInsertability(field);
        });
    }

    private void applyColumnEditability(Field field, String primaryKey) {
        if (identifierEquals(field.getSourceColumn(), primaryKey)) {
            field.setPrimaryKey(true);
            field.setEditable(false);
            field.setEditReason(EditabilityReason.PRIMARY_KEY_COLUMN_NOT_EDITABLE);
            return;
        }
        if (field.isMasked()) {
            field.setEditable(false);
            field.setEditReason(EditabilityReason.DATA_MASKED);
            return;
        }
        if (field.isAutoIncrement() || field.isReadOnly() || field.isGenerated()) {
            field.setEditable(false);
            field.setEditReason(TableChangesPlanBuilder.SOURCE_COLUMN_NOT_EDITABLE);
            return;
        }
        if (!TableEditTypeCodecs.supports(field, this.dbType)) {
            field.setEditable(false);
            field.setEditReason(EditabilityReason.TYPE_NOT_SUPPORTED_FOR_EDIT);
            return;
        }
        field.setEditable(true);
        field.setEditReason(null);
    }

    private void markAllReadOnly(List<Field> fields, String reason) {
        fields.forEach(field -> {
            field.setEditable(false);
            field.setEditReason(reason);
            field.setInsertable(false);
            field.setInsertReason(reason);
            field.setRequiredOnInsert(false);
        });
    }

    private void applyInsertability(Field field) {
        if (field.isMasked()) {
            this.markNotInsertable(field, EditabilityReason.DATA_MASKED);
            return;
        }
        if (!TableEditTypeCodecs.supports(field, this.dbType)) {
            this.markNotInsertable(field, EditabilityReason.TYPE_NOT_SUPPORTED_FOR_EDIT);
            return;
        }
        if (field.isAutoIncrement() || field.isReadOnly() || field.isGenerated()) {
            this.markNotInsertable(field, TableChangesPlanBuilder.INSERT_COLUMN_NOT_WRITABLE);
            return;
        }
        if (StringUtils.isBlank(field.getSourceColumn())) {
            this.markNotInsertable(field, EditabilityReason.UNKNOWN_COLUMN_SOURCE);
            return;
        }
        field.setInsertable(true);
        field.setInsertReason(null);
        field.setRequiredOnInsert(!field.isNullable());
    }

    private void markNotInsertable(Field field, String reason) {
        field.setInsertable(false);
        field.setInsertReason(reason);
        field.setRequiredOnInsert(false);
    }

    private static boolean matchesTableOwner(String owner, String table, String tableAlias) {
        if (StringUtils.isBlank(owner)) {
            return true;
        }
        return identifierEquals(owner, tableAlias) || identifierEquals(owner, table);
    }

    private static boolean identifierEquals(String left, String right) {
        return StringUtils.equalsIgnoreCase(normalizeIdentifier(left), normalizeIdentifier(right));
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String value = identifier.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("`") && value.endsWith("`")) ||
                (value.startsWith("[") && value.endsWith("]"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String canonicalizeSourceSchema(String schema, List<Field> fields, boolean quoted) {
        if (!this.shouldCanonicalizeFromMetadata()) {
            return schema;
        }
        return this.canonicalizeFromFieldMetadata(schema, fields, MetadataKind.SCHEMA, quoted);
    }

    private String canonicalizeSourceTable(String table, List<Field> fields, boolean quoted) {
        if (!this.shouldCanonicalizeFromMetadata()) {
            return table;
        }
        return this.canonicalizeFromFieldMetadata(table, fields, MetadataKind.TABLE, quoted);
    }

    private String canonicalizeSourceColumn(Field field, String column, boolean quoted) {
        if (!this.shouldCanonicalizeFromMetadata() || field == null) {
            return column;
        }
        if (quoted && this.isDamengOrDb2()) {
            return column;
        }
        String metadataColumn = normalizeIdentifier(field.getColumnName());
        if (StringUtils.isBlank(metadataColumn)) {
            return column;
        }
        if (StringUtils.isBlank(column) || identifierEquals(metadataColumn, column)) {
            return metadataColumn;
        }
        return this.canonicalizeUnquotedIdentifier(column, quoted);
    }

    private String canonicalizeFromFieldMetadata(String identifier, List<Field> fields, MetadataKind kind, boolean quoted) {
        if (quoted && this.isDamengOrDb2()) {
            return identifier;
        }
        if (fields == null || fields.isEmpty()) {
            return this.canonicalizeUnquotedIdentifier(identifier, quoted);
        }
        String normalizedIdentifier = normalizeIdentifier(identifier);
        String fallback = null;
        for (Field field : fields) {
            if (field == null) {
                continue;
            }
            String metadataIdentifier = switch (kind) {
                case SCHEMA -> normalizeIdentifier(field.getSchema());
                case TABLE -> normalizeIdentifier(field.getTable());
            };
            if (StringUtils.isBlank(metadataIdentifier)) {
                continue;
            }
            if (fallback == null) {
                fallback = metadataIdentifier;
            }
            if (StringUtils.isBlank(normalizedIdentifier) || identifierEquals(metadataIdentifier, normalizedIdentifier)) {
                return metadataIdentifier;
            }
        }
        if (StringUtils.isNotBlank(fallback) && StringUtils.isBlank(normalizedIdentifier)) {
            return fallback;
        }
        return this.canonicalizeUnquotedIdentifier(normalizedIdentifier, quoted);
    }

    private String canonicalizeUnquotedIdentifier(String identifier, boolean quoted) {
        if (quoted || StringUtils.isBlank(identifier)) {
            return identifier;
        }
        if (this.dbType != DbType.oracle && this.dbType != DbType.dm && this.dbType != DbType.db2) {
            return identifier;
        }
        return identifier.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isQuotedIdentifier(String identifier) {
        if (identifier == null) {
            return false;
        }
        String value = identifier.trim();
        return (value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("`") && value.endsWith("`")) ||
                (value.startsWith("[") && value.endsWith("]"));
    }

    private boolean shouldCanonicalizeFromMetadata() {
        return this.dbType == DbType.oracle ||
                this.dbType == DbType.sqlserver ||
                this.dbType == DbType.dm ||
                this.dbType == DbType.db2;
    }

    private boolean isDamengOrDb2() {
        return this.dbType == DbType.dm || this.dbType == DbType.db2;
    }

    private enum MetadataKind {
        SCHEMA,
        TABLE
    }

    private record ColumnResolution(String name, boolean quoted, String reason) {
        static ColumnResolution column(String name, boolean quoted) {
            if (StringUtils.isBlank(name) || Objects.equals(name, "*")) {
                return reason(EditabilityReason.UNKNOWN_COLUMN_SOURCE);
            }
            return new ColumnResolution(name, quoted, null);
        }

        static ColumnResolution reason(String reason) {
            return new ColumnResolution(null, false, reason);
        }
    }

    private record SourceTable(String schema, String table, String alias) {
    }
}
