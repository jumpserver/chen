package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import org.apache.commons.lang3.StringUtils;

public final class SQLIdentifier {
    private SQLIdentifier() {
    }

    public static String quote(DbType dbType, String identifier) {
        if (StringUtils.isBlank(identifier)) {
            throw new IllegalArgumentException("SQL identifier must not be blank");
        }
        for (int i = 0; i < identifier.length(); i++) {
            if (Character.isISOControl(identifier.charAt(i))) {
                throw new IllegalArgumentException("SQL identifier must not contain control characters");
            }
        }

        return switch (dbType) {
            case mysql, mariadb, clickhouse ->
                    "`" + identifier.replace("`", "``") + "`";
            case sqlserver ->
                    "[" + identifier.replace("]", "]]") + "]";
            case postgresql, oracle, db2, dm ->
                    "\"" + identifier.replace("\"", "\"\"") + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + dbType);
        };
    }
}
