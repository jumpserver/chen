package org.jumpserver.chen.framework.datasource.base;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;
import org.jumpserver.chen.framework.session.SessionManager;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseSQLHintsHandler implements SQLHintsHandler {

    public abstract List<Table> getALlTables(String schema) throws SQLException;

    public abstract List<Field> getAllFields(String schema) throws SQLException;

    @Override
    public Map<String, List<String>> getHints(String nodeKey, String context) throws SQLException {
        Map<String, List<String>> suggestions = new HashMap<>();

        var session = SessionManager.getCurrentSession();
        if (!session.enableAutoComplete()) {
            return suggestions;
        }

        var tables = this.getALlTables(context);
        var tableNames = tables.stream().map(Table::getName).toList();

        suggestions.put(context, tableNames);
        var fields = this.getAllFields(context);

        tables.forEach(table -> {
            var fieldsOfTable = fields.stream().filter(field -> field.getTable().equals(table.getName())).toList();
            suggestions.put(table.getName(), fieldsOfTable.stream().map(Field::getName).toList());
        });
        return suggestions;
    }
}
