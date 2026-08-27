package org.jumpserver.chen.framework.datasource.base;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.metadata.MetadataCatalog;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.SqlIdentifierUtils;
import org.jumpserver.chen.framework.utils.TreeUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public abstract class BaseResourceBrowser implements ResourceBrowser {
    @Getter
    private TreeNode root;
    @Getter
    private final ConnectionManager connectionManager;

    private final ConcurrentHashMap<String, ResourceNodeSnapshot> nodeIndex = new ConcurrentHashMap<>();

    public BaseResourceBrowser(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void buildTree() throws SQLException {
        var root = new Root();

        root.setName(SessionManager.getCurrentSession().getDatasourceName());
        this.resetNodeIndex(root.toResourceNode(null));

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
        var cachedNode = TreeUtils.getNode(this.root, node.getKey());
        var children = this.getChildNodes(node);
        if (!children.isEmpty() || cachedNode != null && cachedNode.getChildren() != null) {
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
        return this.metadataCatalog().listSchemas(null).stream()
                .map(schema -> {
                    var entity = new Schema();
                    entity.setName(schema.name());
                    return entity.toResourceNode(parent);
                })
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
        String database = TreeUtils.getValue(parent.getKey(), "database");
        var scope = new RelationScope(database.isEmpty() ? null : database, schema);
        return switch (Objects.requireNonNull(folder)) {
            case "tables" -> this.metadataCatalog().listRelations(scope, Set.of(RelationKind.TABLE))
                    .stream()
                    .map(relation -> {
                        var table = new Table();
                        table.setName(relation.ref().name());
                        table.setSchema(relation.ref().schema());
                        return table.toResourceNode(parent);
                    })
                    .toList();
            case "views" -> this.metadataCatalog().listRelations(
                            scope, Set.of(RelationKind.VIEW, RelationKind.MATERIALIZED_VIEW))
                    .stream()
                    .map(relation -> {
                        var view = new View();
                        view.setName(relation.ref().name());
                        view.setSchema(relation.ref().schema());
                        return view.toResourceNode(parent);
                    })
                    .toList();
            default -> List.of();
        };
    }

    public synchronized void saveTreeNode(TreeNode node, List<TreeNode> children) {
        var n = TreeUtils.getNode(this.root, node.getKey());
        if (n != null) {
            n.setChildren(children);
            this.removeIndexedDescendants(n.getKey());
            var parent = this.nodeIndex.get(n.getKey());
            for (var child : children) {
                this.registerNode(child, parent);
            }
        }
    }

    protected synchronized void resetNodeIndex(TreeNode root) {
        this.root = root;
        this.nodeIndex.clear();
        this.registerNode(root, null);
    }

    @Override
    public synchronized ResourceNodeSnapshot getIndexedNode(String key) {
        return key == null ? null : this.nodeIndex.get(key);
    }

    private void registerNode(TreeNode node, ResourceNodeSnapshot parent) {
        String database = parent == null ? null : parent.database();
        String schema = parent == null ? null : parent.schema();
        String table = parent == null ? null : parent.table();

        switch (node.getType()) {
            case "database" -> {
                database = node.getLabel();
                schema = null;
                table = null;
            }
            case "schema" -> {
                schema = node.getLabel();
                if (this.connectionManager != null &&
                        Objects.equals(this.connectionManager.getDatabaseContextKey(), "schema")) {
                    database = node.getLabel();
                }
                table = null;
            }
            case "table", "view" -> table = node.getLabel();
            default -> {
            }
        }

        this.nodeIndex.put(node.getKey(), new ResourceNodeSnapshot(
                node.getKey(),
                node.getType(),
                database,
                schema,
                table,
                node.getLabel()
        ));
        if (node.getChildren() != null) {
            var snapshot = this.nodeIndex.get(node.getKey());
            for (var child : node.getChildren()) {
                this.registerNode(child, snapshot);
            }
        }
    }

    private void removeIndexedDescendants(String parentKey) {
        String prefix = parentKey + ",";
        this.nodeIndex.keySet().removeIf(key -> key.startsWith(prefix));
    }


    @Override
    public RelationScope resolveScope(ResourceNodeSnapshot node, String context) throws SQLException {
        if (node == null) {
            throw new IllegalArgumentException("Invalid metadata context");
        }
        var contextKey = this.connectionManager.getContextKey();
        var databaseContextKey = this.connectionManager.getDatabaseContextKey();
        var currentContext = StringUtils.defaultString(context).trim();
        var catalog = node.database();
        if (StringUtils.equals(contextKey, databaseContextKey) && StringUtils.isNotBlank(currentContext)) {
            var allowedContexts = this.connectionManager.getSqlActuator().getSchemas();
            if (!allowedContexts.contains(currentContext)) {
                throw new IllegalArgumentException("Unknown database metadata context");
            }
            catalog = currentContext;
        }
        if (StringUtils.equals(databaseContextKey, "schema")) {
            var schema = StringUtils.defaultIfBlank(currentContext, node.schema());
            SqlIdentifierUtils.validateDatabaseName(schema);
            return new RelationScope(null, schema);
        }
        SqlIdentifierUtils.validateDatabaseName(catalog);
        String schema = null;
        if (StringUtils.equals(contextKey, "schema")) {
            schema = StringUtils.defaultIfBlank(currentContext, node.schema());
            if (StringUtils.isNotBlank(catalog) && schema.startsWith(catalog + ".")) {
                schema = schema.substring(catalog.length() + 1);
            }
        } else if (StringUtils.equals(node.database(), catalog)) {
            schema = node.schema();
        }
        return new RelationScope(catalog, schema);
    }

    protected MetadataCatalog metadataCatalog() {
        return this.connectionManager.getDatasource().getMetadataCatalog();
    }

}
