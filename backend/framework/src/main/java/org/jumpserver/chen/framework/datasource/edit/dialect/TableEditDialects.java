package org.jumpserver.chen.framework.datasource.edit.dialect;

import com.alibaba.druid.DbType;

import java.util.Optional;

public final class TableEditDialects {
    private static final TableEditDialect POSTGRESQL = new PostgresqlTableEditDialect();
    private static final TableEditDialect MYSQL = new MysqlTableEditDialect();
    private static final TableEditDialect ORACLE = new OracleTableEditDialect();
    private static final TableEditDialect SQLSERVER = new SqlServerTableEditDialect();
    private static final TableEditDialect DAMENG = new DamengTableEditDialect();
    private static final TableEditDialect DB2 = new Db2TableEditDialect();

    private TableEditDialects() {
    }

    public static Optional<TableEditDialect> find(DbType dbType) {
        if (dbType == DbType.postgresql) {
            return Optional.of(POSTGRESQL);
        }
        if (dbType == DbType.mysql || dbType == DbType.mariadb) {
            return Optional.of(MYSQL);
        }
        if (dbType == DbType.oracle) {
            return Optional.of(ORACLE);
        }
        if (dbType == DbType.sqlserver) {
            return Optional.of(SQLSERVER);
        }
        if (dbType == DbType.dm) {
            return Optional.of(DAMENG);
        }
        if (dbType == DbType.db2) {
            return Optional.of(DB2);
        }
        return Optional.empty();
    }
}
