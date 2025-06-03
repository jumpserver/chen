package org.jumpserver.chen.modules.sqlserver;

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


public class SQLServerResourceBrowser extends BaseResourceBrowser {
    public SQLServerResourceBrowser(ConnectionManager connectionManager) {

        super(connectionManager, new SQLServerSQLHintsHandler(connectionManager));
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

    private static final String SQL_GET_DATABASES = "SELECT name FROM sys.databases;";

    public List<Database> getDatabases() throws SQLException {
        return this.getDatabases(SQL.of(SQL_GET_DATABASES));
    }

    public List<Database> getDatabases(SQL sql) throws SQLException {
        return this.getConnectionManager()
                .getSqlActuator()
                .getObjects(sql.getSql(), Database.class, Map.of("name", 1));
    }

    // 查询当前数据库下所有的schema
    private static final String SQL_GET_SCHEMAS = "SELECT schema_name FROM information_schema.schemata";

    @Override
    public List<Schema> getSchemas() throws SQLException {
        return this.getSchemas(SQL.of(SQL_GET_SCHEMAS));
    }

    private static final String SQL_GET_TABLES = " SELECT table_name AS name FROM information_schema.tables WHERE table_schema = '?'";

    @Override
    public List<Table> getTables(String schema) throws SQLException {
        return this.getTables(SQL.of(SQL_GET_TABLES, schema));
    }

    private static final String SQL_GET_VIEWS = "SELECT table_name AS name FROM information_schema.tables WHERE table_schema = '?' AND table_type = 'VIEW'";

    @Override
    public List<View> getViews(String schema) throws SQLException {
        return this.getViews(SQL.of(SQL_GET_VIEWS, schema));
    }

    private static final String SQL_GET_FIELDS = "SELECT column_name AS name, column_type AS type, column_key AS `key`, is_nullable AS `nullable`, column_default AS `default`, extra AS extra, column_comment AS comment FROM information_schema.columns WHERE table_schema = '?' AND table_name = '?'";

    @Override
    public List<Field> getFields(String schema, String table) throws SQLException {
        return this.getFields(SQL.of(SQL_GET_FIELDS, schema, table));
    }


}
