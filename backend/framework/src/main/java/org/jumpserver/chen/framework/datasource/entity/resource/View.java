package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class View extends Table {
    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setLabel(this.name);
        treeNode.setType("view");
        treeNode.setHasChildren(false);
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), name));
        return treeNode;
    }
}
