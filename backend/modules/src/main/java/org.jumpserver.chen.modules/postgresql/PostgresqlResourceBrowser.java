package org.jumpserver.chen.modules.postgresql;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.modules.postgresql.entity.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;


public class PostgresqlResourceBrowser extends BaseResourceBrowser {
    public PostgresqlResourceBrowser(ConnectionManager connectionManager) {
        super(connectionManager, new PostgresqlSQLHintsHandler(connectionManager));
    }

    @Override
    public List<TreeNode> getDatasourceChildNodes(TreeNode parent) throws SQLException {
        return this.getDatabases()
                .stream()
                .map(database -> database.toResourceNode(parent))
                .toList();
    }

    public List<TreeNode> getChildNodes(TreeNode node) throws SQLException {
        if (node != null) {
            var dbName = TreeUtils.getValue(node.getKey(), "database");
            if (StringUtils.isNotEmpty(dbName)) {
                this.getConnectionManager().setDatabaseContext(dbName);
            }
        }
        return super.getChildNodes(node);
    }

    public List<TreeNode> getDatabaseChildNodes(TreeNode node) throws SQLException {
        return this.getSchemas()
                .stream()
                .map(schema -> schema.toResourceNode(node))
                .toList();
    }

    private static final String SQL_GET_DATABASES = "SELECT DATNAME AS NAME FROM PG_DATABASE WHERE DATISTEMPLATE = FALSE";

    public List<Database> getDatabases() throws SQLException {
        return this.getDatabases(SQL.of(SQL_GET_DATABASES));
    }

    public List<Database> getDatabases(SQL sql) throws SQLException {
        return this.getConnectionManager()
                .getSqlActuator()
                .getObjects(sql.getSql(), Database.class, Map.of("name", 1));
    }

    private static final String SQL_GET_SCHEMAS = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = " SELECT TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '?' AND TABLE_TYPE = 'BASE TABLE'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "SELECT TABLE_NAME AS NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '?' AND TABLE_TYPE = 'VIEW'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT COLUMN_NAME AS NAME, COLUMN_TYPE AS TYPE, COLUMN_KEY AS `KEY`, IS_NULLABLE AS `NULLABLE`, COLUMN_DEFAULT AS `DEFAULT`, EXTRA AS EXTRA, COLUMN_COMMENT AS COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '?' AND TABLE_NAME = '?'";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }


}
