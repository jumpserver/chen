package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.utils.TreeUtils;

@Data
public class View extends Table {
    private RelationKind relationKind = RelationKind.VIEW;

    public void setRelationKind(RelationKind relationKind) {
        if (relationKind != RelationKind.VIEW && relationKind != RelationKind.MATERIALIZED_VIEW) {
            throw new IllegalArgumentException("View nodes require a view relation kind");
        }
        this.relationKind = relationKind;
    }

    @Override
    public TreeNode toResourceNode(TreeNode parent) {
        TreeNode treeNode = new TreeNode();
        treeNode.setLabel(this.name);
        treeNode.setType("view");
        treeNode.setRelationKind(this.relationKind.code());
        treeNode.setHasChildren(false);
        treeNode.setKey(TreeUtils.generateNodeKey(parent, treeNode.getType(), name));
        return treeNode;
    }
}
