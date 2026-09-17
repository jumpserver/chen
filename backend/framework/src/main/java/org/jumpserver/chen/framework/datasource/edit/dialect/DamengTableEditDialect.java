package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

class DamengTableEditDialect extends AbstractTableEditDialect {
    DamengTableEditDialect() {
        super(DbType.dm);
    }
}
