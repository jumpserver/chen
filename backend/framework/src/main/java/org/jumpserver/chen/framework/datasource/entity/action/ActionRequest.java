package org.jumpserver.chen.framework.datasource.entity.action;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;

@Data
public class ActionRequest {
    private String action;
    private TreeNode node;
}
