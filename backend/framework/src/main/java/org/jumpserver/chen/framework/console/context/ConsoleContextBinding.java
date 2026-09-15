package org.jumpserver.chen.framework.console.context;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Applies a resolved {@link ConsoleContext} onto JDBC database selection and the
 * console UI context. Mirrors {@code ConnectionManager.getContextKey()} /
 * {@code getDatabaseContextKey()} without encoding vendor names here.
 */
public final class ConsoleContextBinding {
    private ConsoleContextBinding() {
    }

    /**
     * JDBC catalog/database used to open a physical connection.
     * When the UI context itself is the JDBC database (MySQL schema, SQL Server
     * database), prefer the live current context so a manual switch survives
     * reconnect. Otherwise always use {@link ConsoleContext#database()} so a
     * PostgreSQL schema is never treated as a database, and a database-node
     * console still connects to the selected catalog.
     */
    public static String physicalDatabase(
            ConsoleContext context,
            String contextKey,
            String databaseContextKey,
            String currentContext
    ) {
        if (context == null) {
            return null;
        }
        if (StringUtils.equals(contextKey, databaseContextKey)) {
            if (StringUtils.isNotBlank(currentContext)) {
                return currentContext;
            }
            if (StringUtils.isNotBlank(context.database())) {
                return context.database();
            }
            return context.schema();
        }
        return context.database();
    }

    /**
     * UI/session schema (or database, when {@code contextKey} is database) after
     * the physical connection is open. A schema-based engine opened from a
     * database node has no selected schema: prefer {@code public}, then the
     * server default, then the first available schema. Missing {@code public}
     * is not an error.
     */
    public static String initialUiContext(
            String requested,
            String nodeType,
            String contextKey,
            List<String> available,
            String currentSchema
    ) {
        if (StringUtils.isNotBlank(requested)) {
            return matchAvailableContext(requested, available);
        }
        if ("database".equals(nodeType) && "schema".equals(contextKey)) {
            return defaultSchemaContext(available, currentSchema);
        }
        return currentSchema;
    }

    public static String matchAvailableContext(String requested, List<String> available) {
        if (StringUtils.isBlank(requested) || available == null || available.isEmpty()) {
            return requested;
        }
        for (String item : available) {
            if (requested.equals(item)) {
                return item;
            }
        }
        String suffix = "." + requested;
        for (String item : available) {
            if (item != null && item.endsWith(suffix)) {
                return item;
            }
        }
        return requested;
    }

    static String defaultSchemaContext(List<String> available, String currentSchema) {
        String publicContext = findNamedSchema(available, "public");
        if (publicContext != null) {
            return publicContext;
        }
        String matchedCurrent = matchAvailableContext(currentSchema, available);
        if (isPresent(available, matchedCurrent)) {
            return matchedCurrent;
        }
        if (StringUtils.isNotBlank(currentSchema)) {
            return currentSchema;
        }
        if (available != null) {
            for (String item : available) {
                if (StringUtils.isNotBlank(item)) {
                    return item;
                }
            }
        }
        return currentSchema;
    }

    private static String findNamedSchema(List<String> available, String name) {
        if (available == null || StringUtils.isBlank(name)) {
            return null;
        }
        for (String item : available) {
            if (name.equals(item) || (item != null && item.endsWith("." + name))) {
                return item;
            }
        }
        return null;
    }

    private static boolean isPresent(List<String> available, String value) {
        return StringUtils.isNotBlank(value) && available != null && available.contains(value);
    }
}
