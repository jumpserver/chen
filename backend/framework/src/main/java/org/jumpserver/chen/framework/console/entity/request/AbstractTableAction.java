package org.jumpserver.chen.framework.console.entity.request;

public abstract class AbstractTableAction {
    private String database;
    private String table;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }
}
