package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class PostgresqlTableEditDialect extends AbstractTableEditDialect {
    PostgresqlTableEditDialect() {
        super(DbType.postgresql);
    }

    @Override
    protected String buildPreparedOldValueCondition(String quotedSourceColumn) {
        return quotedSourceColumn + " IS NOT DISTINCT FROM ?";
    }

    @Override
    protected String buildAuditOldValueCondition(String quotedSourceColumn, String renderedOldValue) {
        return quotedSourceColumn + " IS NOT DISTINCT FROM " + renderedOldValue;
    }
}
