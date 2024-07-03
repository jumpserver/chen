package org.jumpserver.chen.framework.datasource.base;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Slf4j
public abstract class BaseResourceBrowser implements ResourceBrowser {
    @Getter
    private TreeNode root;
    @Getter
    private final ConnectionManager connectionManager;

    private final SQLHintsHandler sqlHintsHandler;


    @Override
    public SQLHintsHandler getSQLHintsHandler() {
        return this.sqlHintsHandler;
    }

    public BaseResourceBrowser(ConnectionManager connectionManager, SQLHintsHandler sqlHintsHandler) {
        this.connectionManager = connectionManager;
        this.sqlHintsHandler = sqlHintsHandler;
    }

    @Override
    public void buildTree() throws SQLException {
        var root = new Root();

        root.setName(SessionManager.getCurrentSession().getDatasourceName());
        this.root = root.toResourceNode(null);

        var parents = List.of(this.root);
        while (!parents.isEmpty()) {
            var children = new ArrayList<TreeNode>();
            for (var parent : parents) {
                if (parent.getType().equals("database") &&
                        !Objects.equals(TreeUtils.getValue(parent.getKey(), "database"), this.connectionManager.getConnectInfo().getDb())) {
                    continue;
                }
                if (parent.getType().equals("schema") &&
                        !Objects.equals(TreeUtils.getValue(parent.getKey(), "schema"), this.connectionManager.getConnectInfo().getDb())) {
                    continue;
                }
                parent.setChildren(this.getChildren(parent, false));
                children.addAll(parent.getChildren());
            }
            parents = children;
        }
    }

    @Override
    public TreeNode getTree() {
        return this.root;
    }

    @Override
    public List<TreeNode> getChildren(TreeNode node) throws SQLException {
        return this.getChildren(node, true);
    }

    @Override
    public List<TreeNode> getChildren(TreeNode node, boolean fromCache) throws SQLException {
        if (node == null) {
            return List.of(this.root);
        }
        if (fromCache) {
            var n = TreeUtils.getNode(this.root, node.getKey());
            if (n != null && n.getChildren() != null) {
                return n.getChildren();
            }
        }
        var children = this.getChildNodes(node);
        if (!children.isEmpty()) {
            this.saveTreeNode(node, children);
        }
        return children;
    }

    public List<TreeNode> getChildNodes(TreeNode node) throws SQLException {
        if (node == null) {
            return List.of(this.root);
        }
        if (!node.isHasChildren()) {
            return List.of();
        }
        String targetMethodName = String.format("get%sChildNodes", node.getType().substring(0, 1).toUpperCase() + node.getType().substring(1));
        try {
            return (List<TreeNode>) this.getClass()
                    .getMethod(targetMethodName, TreeNode.class)
                    .invoke(this, node);
        } catch (InvocationTargetException e) {
            throw new SQLException(e.getTargetException());
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public List<TreeNode> getDatasourceChildNodes(TreeNode parent) throws SQLException {
        return this.getSchemas()
                .stream()
                .map(schema -> schema.toResourceNode(parent))
                .toList();
    }

    public List<TreeNode> getSchemaChildNodes(TreeNode parent) throws SQLException {
        return List.of(
                Folder.of("tables", "schema").toResourceNode(parent),
                Folder.of("views", "schema").toResourceNode(parent)
        );
    }

    public List<TreeNode> getFolderChildNodes(TreeNode parent) throws SQLException {
        String folder = TreeUtils.getValue(parent.getKey(), "folder");
        String schema = TreeUtils.getValue(parent.getKey(), "schema");
        return switch (Objects.requireNonNull(folder)) {
            case "tables" -> this.getTables(schema)
                    .stream()
                    .map(table -> table.toResourceNode(parent))
                    .toList();
            case "views" -> this.getViews(schema)
                    .stream()
                    .map(view -> view.toResourceNode(parent))
                    .toList();
            default -> List.of();
        };
    }

    public void saveTreeNode(TreeNode node, List<TreeNode> children) {
        var n = TreeUtils.getNode(this.root, node.getKey());
        if (n != null) {
            n.setChildren(children);
        }
    }

    public abstract List<Schema> getSchemas() throws SQLException;

    @Override
    public List<Schema> getSchemas(SQL sql) throws SQLException {
        var currentSchema = "";
        List<Schema> schemas = new ArrayList<>();
        schemas.addAll(this.getSQLActuator().getObjects(sql.getSql(), Schema.class, Map.of("name", 1)));
        schemas.sort((o1, o2) -> {
            if (o1.getName().equalsIgnoreCase(currentSchema)) {
                return -1;
            } else if (o2.getName().equalsIgnoreCase(currentSchema)) {
                return 1;
            } else {
                return 0;
            }
        });
        return schemas;
    }

    public abstract List<Table> getTables(String schema) throws SQLException;

    @Override
    public List<Table> getTables(SQL sql) throws SQLException {
        return new ArrayList<>(this.getSQLActuator().getObjects(sql.getSql(), Table.class, Map.of("name", 1)));
    }

    public abstract List<View> getViews(String schema) throws SQLException;

    @Override
    public List<View> getViews(SQL sql) throws SQLException {
        return new ArrayList<>(this.getSQLActuator().getObjects(sql.getSql(), View.class, Map.of("name", 1)));
    }

    public abstract List<Field> getFields(String schema, String table) throws SQLException;

    @Override
    public List<Field> getFields(SQL sql) throws SQLException {
        Map<String, Integer> fieldMapping = Map.of(
                "name", 1,
                "type", 2,
                "nullable", 3);
        return new ArrayList<>(this.getSQLActuator().getObjects(sql.getSql(), Field.class, fieldMapping));
    }

    public SQLActuator getSQLActuator() {
        return this.connectionManager.getSqlActuator();
    }

}
