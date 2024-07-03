package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class Field implements ResourceNode {

    private String name;
    private String schema;
    private String table;
    private String type;
    private boolean nullable;
    private boolean isPrimaryKey;

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
