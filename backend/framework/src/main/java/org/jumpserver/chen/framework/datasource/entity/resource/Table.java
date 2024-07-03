package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class Table implements ResourceNode {
    protected String name;
    private String schema;


    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setLabel(this.name);
        treeNode.setType("table");
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), name));
        treeNode.setHasChildren(false);
        return treeNode;
    }
}
