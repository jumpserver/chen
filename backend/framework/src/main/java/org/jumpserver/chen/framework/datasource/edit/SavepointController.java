package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.sql.SQLException;

interface SavepointController {
    void create(Connection connection, String name) throws SQLException;

    void rollbackTo(Connection connection, String name) throws SQLException;

    default void release(Connection connection, String name) throws SQLException {
        // A dialect without RELEASE SAVEPOINT support may intentionally leave it active.
    }
}
