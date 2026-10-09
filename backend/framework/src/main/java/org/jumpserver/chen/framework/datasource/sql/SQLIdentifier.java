package org.jumpserver.chen.framework.datasource.sql;

/** Quotes one metadata identifier, never an SQL expression or qualified path. */
public final class SQLIdentifier {
    private SQLIdentifier() {}

    public static String mysql(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

}
