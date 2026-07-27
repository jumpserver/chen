package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class Field implements ResourceNode {

    private String name;
    private String label;
    private String columnName;
    private String schema;
    private String table;
    private String type;
    private transient Integer jdbcType;
    private String sourceSchema;
    private String sourceTable;
    private String sourceColumn;
    private boolean nullable;
    private boolean editable;
    private String editReason;
    private boolean isPrimaryKey;
    private boolean masked;
    private boolean autoIncrement;
    private boolean readOnly;
    private transient boolean generated;
    private boolean insertable;
    private boolean requiredOnInsert;
    private String insertReason;

    public void setNullable(String nullable) {
        String[] trueAlias = {"YES", "Y"};
        for (String alias : trueAlias) {
            if (alias.equalsIgnoreCase(nullable)) {
                this.nullable = true;
                return;
            }
        }
        this.nullable = false;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }


    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setType("field");
        treeNode.setLabel(String.format("%s(%s)", name, type));
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), name));
        return treeNode;
    }
}
