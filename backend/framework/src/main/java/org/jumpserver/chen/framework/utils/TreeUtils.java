package org.jumpserver.chen.framework.utils;

import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;

public class TreeUtils {
    public static final String NODE_SPLIT = ",";
    public static final String TYPE_SPLIT = ":";

    public static String generateNodeKey(TreeNode parent, String type, String name) {
        if (parent == null) {
            return generateOneNodeKey(type, name);
        }
        return String.format("%s%s%s", parent.getKey(), NODE_SPLIT, generateOneNodeKey(type, name));
    }

    private static String generateOneNodeKey(String type, String name) {
        return String.format("%s%s%s", type, TYPE_SPLIT, name);
    }


    public static String getParentKey(String key) {
        String[] keys = key.split(NODE_SPLIT);
        if (keys.length == 1) {
            return null;
        }
        return keys[keys.length - 2];
    }

    public static String getNodeType(String key) {
        String[] keys = key.split(NODE_SPLIT);
        String lastKey = keys[keys.length - 1];
        return lastKey.split(TYPE_SPLIT)[0];
    }

    public static String getValue(String key, String type) {
        String[] keys = key.split(NODE_SPLIT);
        for (int i = keys.length - 1; i >= 0; i--) {
            String[] node = keys[i].split(TYPE_SPLIT);
            if (node[0].equals(type)) {
                return node[1];
            }
        }
        return "";
    }

    public static TreeNode getNode(TreeNode root, String key) {
        if (root.getKey().equals(key)) {
            return root;
        }
        if (root.getChildren() == null) {
            return null;
        }
        for (var child : root.getChildren()) {
            var node = getNode(child, key);
            if (node != null) {
                return node;
            }
        }
        return null;
    }
}
