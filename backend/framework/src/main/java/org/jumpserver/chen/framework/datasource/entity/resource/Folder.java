package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
@AllArgsConstructor(staticName = "of")
public class Folder implements ResourceNode {
    private String name;
    private String scope;

    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setType("folder");
        treeNode.setLabel(String.format("%s", this.name));
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), this.name));
        return treeNode;
    }
}
