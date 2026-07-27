package org.jumpserver.chen.framework.datasource.edit;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

final class SqlSavepointController implements SavepointController {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private final String createSql;
    private final String rollbackSql;
    private final String releaseSql;

    SqlSavepointController(String createSql, String rollbackSql, String releaseSql) {
        this.createSql = createSql;
        this.rollbackSql = rollbackSql;
        this.releaseSql = releaseSql;
    }

    @Override
    public void create(Connection connection, String name) throws SQLException {
        execute(connection, this.createSql, name);
    }

    @Override
    public void rollbackTo(Connection connection, String name) throws SQLException {
        execute(connection, this.rollbackSql, name);
    }

    @Override
    public void release(Connection connection, String name) throws SQLException {
        validateName(name);
        if (this.releaseSql != null) {
            execute(connection, this.releaseSql, name);
        }
    }

    static void validateName(String name) throws SQLException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new SQLException("Invalid server-generated savepoint name");
        }
    }

    private void execute(Connection connection, String template, String name) throws SQLException {
        validateName(name);
        try (Statement statement = connection.createStatement()) {
            statement.execute(template.formatted(name));
        }
    }
}
