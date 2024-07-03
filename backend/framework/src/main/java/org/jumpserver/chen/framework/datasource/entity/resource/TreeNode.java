package org.jumpserver.chen.framework.datasource.entity.resource;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TreeNode {
    private String key;
    private String type;
    private String label;
    private Map<String, Object> meta;
    private boolean hasChildren = true;
    private List<TreeNode> children;

}
