package org.jumpserver.chen.framework.datasource.edit;

import com.alibaba.druid.DbType;

final class SavepointControllers {
    private static final SavepointController STANDARD = new SqlSavepointController(
            "SAVEPOINT %s",
            "ROLLBACK TO SAVEPOINT %s",
            "RELEASE SAVEPOINT %s"
    );
    private static final SavepointController ORACLE = new SqlSavepointController(
            "SAVEPOINT %s",
            "ROLLBACK TO SAVEPOINT %s",
            null
    );
    private static final SavepointController SQL_SERVER = new SqlSavepointController(
            "SAVE TRANSACTION %s",
            "ROLLBACK TRANSACTION %s",
            null
    );
    private static final SavepointController DB2 = new SqlSavepointController(
            "SAVEPOINT %s ON ROLLBACK RETAIN CURSORS",
            "ROLLBACK TO SAVEPOINT %s",
            "RELEASE SAVEPOINT %s"
    );

    private SavepointControllers() {
    }

    static SavepointController forDbType(DbType dbType) {
        return switch (dbType) {
            case postgresql, mysql, mariadb, dm -> STANDARD;
            case oracle -> ORACLE;
            case sqlserver -> SQL_SERVER;
            case db2 -> DB2;
            default -> throw new IllegalArgumentException(
                    "USER_MANAGED DataView savepoints are not supported for database: " + dbType
            );
        };
    }
}
