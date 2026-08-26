package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class SqlServerTableEditDialect extends AbstractTableEditDialect {
    SqlServerTableEditDialect() {
        super(DbType.sqlserver);
    }

    @Override
    public int oldValueParameterCount() {
        return 2;
    }

    @Override
    protected String buildPreparedOldValueCondition(String quotedSourceColumn) {
        return this.buildPreparedNullableEqualityCondition(quotedSourceColumn);
    }

    @Override
    protected String buildAuditOldValueCondition(String quotedSourceColumn, String renderedOldValue) {
        return this.buildAuditNullableEqualityCondition(quotedSourceColumn, renderedOldValue);
    }
}
