package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class OracleTableEditDialect extends AbstractTableEditDialect {
    OracleTableEditDialect() {
        super(DbType.oracle);
    }
}
