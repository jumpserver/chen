package org.jumpserver.chen.framework.datasource.edit.pk;

import java.sql.SQLException;

@FunctionalInterface
public interface PrimaryKeyResolver {
    PrimaryKeyResolution resolvePrimaryKeys(String schema, String table) throws SQLException;
}
