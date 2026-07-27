package org.jumpserver.chen.framework.datasource.edit.analyzer;

public final class EditabilityReason {
    public static final String NOT_SELECT = "NOT_SELECT";
    public static final String MULTIPLE_STATEMENTS_NOT_SUPPORTED = "MULTIPLE_STATEMENTS_NOT_SUPPORTED";
    public static final String QUERY_BLOCK_NOT_SUPPORTED = "QUERY_BLOCK_NOT_SUPPORTED";
    public static final String CTE_NOT_SUPPORTED = "CTE_NOT_SUPPORTED";
    public static final String SET_OPERATION_NOT_SUPPORTED = "SET_OPERATION_NOT_SUPPORTED";
    public static final String JOIN_NOT_SUPPORTED = "JOIN_NOT_SUPPORTED";
    public static final String SUBQUERY_NOT_SUPPORTED = "SUBQUERY_NOT_SUPPORTED";
    public static final String TABLE_SOURCE_NOT_SUPPORTED = "TABLE_SOURCE_NOT_SUPPORTED";
    public static final String VIEW_NOT_SUPPORTED = "VIEW_NOT_SUPPORTED";
    public static final String DISTINCT_NOT_SUPPORTED = "DISTINCT_NOT_SUPPORTED";
    public static final String GROUP_BY_NOT_SUPPORTED = "GROUP_BY_NOT_SUPPORTED";
    public static final String HAVING_NOT_SUPPORTED = "HAVING_NOT_SUPPORTED";
    public static final String EXPRESSION_COLUMN = "EXPRESSION_COLUMN";
    public static final String AGGREGATE_OR_FUNCTION_COLUMN = "AGGREGATE_OR_FUNCTION_COLUMN";
    public static final String ALL_COLUMNS_NOT_SUPPORTED = "ALL_COLUMNS_NOT_SUPPORTED";
    public static final String COLUMN_OWNER_NOT_SUPPORTED = "COLUMN_OWNER_NOT_SUPPORTED";
    public static final String UNKNOWN_COLUMN_SOURCE = "UNKNOWN_COLUMN_SOURCE";
    public static final String SELECT_LIST_MISMATCH = "SELECT_LIST_MISMATCH";
    public static final String PRIMARY_KEY_RESOLUTION_NOT_IMPLEMENTED = "PRIMARY_KEY_RESOLUTION_NOT_IMPLEMENTED";
    public static final String PRIMARY_KEY_RESOLUTION_FAILED = "PRIMARY_KEY_RESOLUTION_FAILED";
    public static final String NO_PRIMARY_KEY = "NO_PRIMARY_KEY";
    public static final String COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED = "COMPOSITE_PRIMARY_KEY_NOT_SUPPORTED";
    public static final String PRIMARY_KEY_NOT_IN_RESULT = "PRIMARY_KEY_NOT_IN_RESULT";
    public static final String PRIMARY_KEY_COLUMN_NOT_EDITABLE = "PRIMARY_KEY_COLUMN_NOT_EDITABLE";
    public static final String DATA_MASKED = "DATA_MASKED";
    public static final String TYPE_NOT_SUPPORTED_FOR_EDIT = "TYPE_NOT_SUPPORTED_FOR_EDIT";

    private EditabilityReason() {
    }
}
