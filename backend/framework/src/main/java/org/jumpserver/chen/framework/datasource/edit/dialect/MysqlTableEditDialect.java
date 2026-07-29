package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class MysqlTableEditDialect extends AbstractTableEditDialect {
    MysqlTableEditDialect() {
        super(DbType.mysql);
    }

    @Override
    protected String buildPreparedOldValueCondition(String quotedSourceColumn) {
        return quotedSourceColumn + " <=> ?";
    }

    @Override
    protected String buildAuditOldValueCondition(String quotedSourceColumn, String renderedOldValue) {
        return quotedSourceColumn + " <=> " + renderedOldValue;
    }
}
