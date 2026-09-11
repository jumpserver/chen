package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class MysqlTableEditDialect extends AbstractTableEditDialect {
    MysqlTableEditDialect() {
        super(DbType.mysql);
    }
}
