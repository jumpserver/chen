package org.jumpserver.chen.modules.dameng;

import org.junit.Test;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.RelationKind;
import org.jumpserver.chen.framework.datasource.metadata.RelationScope;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;

import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmMetadataProviderRelationTest {
    private static final Pattern UNSAFE_DM_ALIAS =
            Pattern.compile("(?i)\\bAS\\s+(?:comment|collation|type)\\b");

    @Test
    public void listTablesUsesReservedWordSafeCommentAliasAndMapsRows() throws Exception {
        var capturedSql = new AtomicReference<String>();
        var provider = new DmMetadataProvider(connectionManager(capturedSql));

        var tables = provider.listRelations(new RelationScope(null, "SYSDBA"), Set.of(RelationKind.TABLE));

        assertEquals(1, tables.size());
        assertEquals(RelationKind.TABLE, tables.get(0).ref().kind());
        assertEquals("ORDERS", tables.get(0).ref().name());
        assertEquals("order table", tables.get(0).comment());
        assertRelationProjection(capturedSql.get(), "all_tables", "t.table_name");
        assertFalse(capturedSql.get(), normalized(capturedSql.get()).contains(" as engine"));
        assertFalse(capturedSql.get(), normalized(capturedSql.get()).contains(" as character_set"));
        assertFalse(capturedSql.get(), normalized(capturedSql.get()).contains(" as collation"));
    }

    @Test
    public void listViewsUsesReservedWordSafeCommentAliasAndMapsRows() throws Exception {
        var capturedSql = new AtomicReference<String>();
        var provider = new DmMetadataProvider(connectionManager(capturedSql));

        var views = provider.listRelations(new RelationScope(null, "SYSDBA"), Set.of(RelationKind.VIEW));

        assertEquals(1, views.size());
        assertEquals(RelationKind.VIEW, views.get(0).ref().kind());
        assertEquals("ACTIVE_ORDERS", views.get(0).ref().name());
        assertEquals("active orders", views.get(0).comment());
        assertRelationProjection(capturedSql.get(), "all_views", "v.view_name");
        assertFalse(capturedSql.get(), normalized(capturedSql.get()).contains(" as type"));
    }

    @Test
    public void listColumnsMapsObjectCommentWithoutReservedAlias() throws Exception {
        var capturedSql = new AtomicReference<String>();
        var provider = new DmMetadataProvider(connectionManager(capturedSql));
        var owner = new ObjectRef(null, "SYSDBA", "ORDERS", RelationKind.TABLE);

        var columns = provider.listColumns(List.of(owner));

        assertEquals(1, columns.size());
        assertEquals("ID", columns.get(0).name());
        assertEquals("primary key", columns.get(0).comment());
        assertReservedWordSafeAliases(capturedSql.get());
        assertTrue(capturedSql.get(), normalized(capturedSql.get()).contains("from all_tab_columns"));
    }

    @Test
    public void metadataQueriesDoNotUseKnownDamengKeywordAliases() throws Exception {
        for (var field : DmMetadataProvider.class.getDeclaredFields()) {
            if (!field.getName().startsWith("SQL_")
                    || field.getType() != String.class
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertReservedWordSafeAliases((String) field.get(null));
        }
    }

    @Test
    public void emptyRelationMetadataRequestsDoNotAccessTheFirstElement() throws Exception {
        var provider = new DmMetadataProvider(connectionManager(new AtomicReference<>()));

        assertTrue(provider.listPrimaryKeys(List.of()).isEmpty());
        assertTrue(provider.listForeignKeys(List.of()).isEmpty());
        assertTrue(provider.listConstraints(List.of()).isEmpty());
    }

    private static void assertRelationProjection(String sql, String source, String nameExpression) {
        var normalized = normalized(sql);
        assertTrue(sql, normalized.contains("select " + nameExpression + " as name, c.comments as object_comment"));
        assertTrue(sql, normalized.contains("from " + source));
        assertTrue(sql, normalized.contains(" as object_comment"));
        assertTrue(sql, sql.contains("?"));
        assertFalse(sql, sql.contains("'?'"));
        assertReservedWordSafeAliases(sql);
    }

    private static void assertReservedWordSafeAliases(String sql) {
        assertFalse(sql, normalized(sql).contains("as \"comment\""));
        assertFalse(sql, UNSAFE_DM_ALIAS.matcher(sql).find());
    }

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static ConnectionManager connectionManager(AtomicReference<String> capturedSql) {
        var actuator = (SQLActuator) Proxy.newProxyInstance(
                SQLActuator.class.getClassLoader(),
                new Class[]{SQLActuator.class},
                (proxy, method, args) -> {
                    if (!"queryRows".equals(method.getName())) {
                        return null;
                    }
                    var sql = (String) args[0];
                    capturedSql.set(sql);
                    var normalized = sql.toLowerCase(Locale.ROOT);
                    if (normalized.contains("from all_tables")) {
                        return List.of(row("name", "ORDERS", "object_comment", "order table"));
                    }
                    if (normalized.contains("from all_views")) {
                        return List.of(row("name", "ACTIVE_ORDERS", "object_comment", "active orders"));
                    }
                    if (normalized.contains("from all_tab_columns")) {
                        return List.of(row(
                                "name", "ID",
                                "table_name", "ORDERS",
                                "ordinal", 1,
                                "native_type", "BIGINT",
                                "jdbc_type_name", "BIGINT",
                                "size", 19,
                                "scale", 0,
                                "nullable", "N",
                                "default_value", null,
                                "object_comment", "primary key"
                        ));
                    }
                    return new ArrayList<>();
                }
        );
        return (ConnectionManager) Proxy.newProxyInstance(
                ConnectionManager.class.getClassLoader(),
                new Class[]{ConnectionManager.class},
                (proxy, method, args) -> "getSqlActuator".equals(method.getName()) ? actuator : null
        );
    }

    private static Map<String, Object> row(Object... values) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }
}
