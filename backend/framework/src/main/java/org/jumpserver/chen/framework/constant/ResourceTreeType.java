package org.jumpserver.chen.framework.constant;

public enum ResourceTreeType {
    DATABASE("database"),
    TABLE("table"),
    COLUMN("column"),
    FOLDER("folder");

    private final String name;

    ResourceTreeType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
