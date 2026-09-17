package org.jumpserver.chen.modules.mariadb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jumpserver.chen.framework.datasource.plan.NormalizedNodeType;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class MariaDBPlanParser {
    public static final String RAW_FORMAT_VERSION = "mariadb-explain-json";
    public static final String ROWS_MEANING = "MariaDB estimated rows";
    public static final String COST_MEANING = "MariaDB cost";
    private static final Set<String> ANALYZE_KEYS = Set.of("r_rows", "r_filtered", "r_loops", "r_total_time_ms");

    private MariaDBPlanParser() {
    }

    public static ParseResult parse(String rawJson, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty MariaDB plan JSON"));
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(rawJson);
        } catch (RuntimeException e) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "MariaDB plan JSON is not valid"));
        }
        JsonObject object = asObject(root);
        if (object == null || !object.has("query_block")) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Unrecognized MariaDB EXPLAIN JSON shape"));
        }
        AtomicInteger ids = new AtomicInteger(1);
        AtomicInteger nodeCount = new AtomicInteger(0);
        List<PlanNode> roots = new ArrayList<>();
        try {
            PlanNode node = convert(object.getAsJsonObject("query_block"), "query_block", ids, nodeCount, 1, maxNodes, maxDepth);
            if (node != null) {
                roots.add(node);
            }
        } catch (LimitReachedException e) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, e.getMessage()));
        }
        if (roots.isEmpty()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "MariaDB plan JSON produced no nodes"));
        }
        return new ParseResult(roots, warnings, true);
    }

    private static PlanNode convert(
            JsonObject object,
            String nativeOperator,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth
    ) {
        if (object == null) {
            return null;
        }
        if (nodeCount.incrementAndGet() > maxNodes || depth > maxDepth) {
            throw new LimitReachedException("Execution plan exceeded size limits");
        }
        JsonObject table = nested(object, "table");
        if (table != null && !"table".equals(nativeOperator) && !object.has("nested_loop") && !object.has("block-nl-join")) {
            List<PlanNode> children = new ArrayList<>();
            children.add(convert(table, "table", ids, nodeCount, depth + 1, maxNodes, maxDepth));
            addChildren(object, ids, nodeCount, depth, maxNodes, maxDepth, children, true);
            return node(ids, nativeOperator, mapNodeType(nativeOperator), text(object, "message"), null, null, decimal(object, "cost"), children, object);
        }

        JsonObject source = "table".equals(nativeOperator) && table != null ? table : object;
        String tableName = text(source, "table_name");
        String access = text(source, "access_type");
        String operator = access != null ? access : nativeOperator;
        BigDecimal rows = decimal(source, "rows");
        BigDecimal cost = firstDecimal(source, "cost", "read_cost", "prefix_cost");
        List<PlanNode> children = new ArrayList<>();
        addChildren(source, ids, nodeCount, depth, maxNodes, maxDepth, children, false);
        if (source != object) {
            addChildren(object, ids, nodeCount, depth, maxNodes, maxDepth, children, true);
        }
        Map<String, String> predicates = new LinkedHashMap<>();
        put(predicates, "attached_condition", text(source, "attached_condition"));
        put(predicates, "message", text(source, "message"));
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "key", text(source, "key"));
        put(attributes, "access_type", access);
        put(attributes, "select_id", text(source, "select_id"));
        return new PlanNode(
                "n" + ids.getAndIncrement(),
                null,
                mapAccess(access, operator),
                operator,
                null,
                operator,
                text(source, "message"),
                tableName,
                tableName,
                rows,
                cost,
                null,
                rows != null ? ROWS_MEANING : null,
                cost != null ? COST_MEANING : null,
                predicates,
                attributes,
                children
        );
    }

    private static void addChildren(
            JsonObject object,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanNode> children,
            boolean skipTable
    ) {
        addNamed(object, "filesort", "filesort", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamed(object, "temporary_table", "temporary_table", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamed(object, "materialized", "materialized", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamed(object, "expression_cache", "expression_cache", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamed(object, "block-nl-join", "block-nl-join", ids, nodeCount, depth, maxNodes, maxDepth, children);
        if (!skipTable) {
            addNamed(object, "table", "table", ids, nodeCount, depth, maxNodes, maxDepth, children);
        }
        JsonElement nestedLoop = object.get("nested_loop");
        if (nestedLoop != null && nestedLoop.isJsonArray()) {
            for (JsonElement item : nestedLoop.getAsJsonArray()) {
                JsonObject child = asObject(item);
                if (child == null) {
                    continue;
                }
                JsonObject nestedTable = nested(child, "table");
                children.add(convert(
                        nestedTable != null ? nestedTable : child,
                        nestedTable != null ? "table" : "nested_loop",
                        ids,
                        nodeCount,
                        depth + 1,
                        maxNodes,
                        maxDepth
                ));
            }
        }
        JsonElement subs = object.get("attached_subqueries");
        if (subs != null && subs.isJsonArray()) {
            for (JsonElement item : subs.getAsJsonArray()) {
                JsonObject child = asObject(item);
                if (child == null) {
                    continue;
                }
                JsonObject block = nested(child, "query_block");
                children.add(convert(block != null ? block : child, "attached_subquery", ids, nodeCount, depth + 1, maxNodes, maxDepth));
            }
        }
    }

    private static void addNamed(
            JsonObject object,
            String key,
            String operator,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanNode> children
    ) {
        JsonObject child = nested(object, key);
        if (child != null) {
            children.add(convert(child, operator, ids, nodeCount, depth + 1, maxNodes, maxDepth));
        }
    }

    static NormalizedNodeType mapAccess(String access, String nativeOperator) {
        String value = (access != null ? access : nativeOperator);
        if (value == null) {
            return NormalizedNodeType.OTHER;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("block-nl") || lower.contains("nested")) {
            return NormalizedNodeType.NESTED_LOOP;
        }
        if (lower.contains("eq_ref") || lower.contains("ref") || lower.contains("range") || lower.contains("index")) {
            return NormalizedNodeType.INDEX_SCAN;
        }
        if (lower.contains("scan") || lower.equals("all") || lower.equals("const") || lower.equals("system")) {
            return NormalizedNodeType.SCAN;
        }
        if (lower.contains("filesort") || lower.contains("sort")) {
            return NormalizedNodeType.SORT;
        }
        if (lower.contains("group") || lower.contains("aggregate")) {
            return NormalizedNodeType.AGGREGATE;
        }
        if (lower.contains("materializ") || lower.contains("temporary")) {
            return NormalizedNodeType.MATERIALIZE;
        }
        if (lower.contains("cache") || lower.contains("expression")) {
            return NormalizedNodeType.OTHER;
        }
        if (lower.contains("subquery") || lower.contains("query_block")) {
            return NormalizedNodeType.SUBQUERY;
        }
        return NormalizedNodeType.OTHER;
    }

    static NormalizedNodeType mapNodeType(String nativeOperator) {
        return mapAccess(null, nativeOperator);
    }

    private static PlanNode node(
            AtomicInteger ids,
            String nativeOperator,
            NormalizedNodeType type,
            String detail,
            String table,
            BigDecimal rows,
            BigDecimal cost,
            List<PlanNode> children,
            JsonObject source
    ) {
        return new PlanNode(
                "n" + ids.getAndIncrement(),
                null,
                type,
                nativeOperator,
                null,
                nativeOperator,
                detail,
                table,
                table,
                rows,
                cost,
                null,
                rows != null ? ROWS_MEANING : null,
                cost != null ? COST_MEANING : null,
                Map.of(),
                Map.of(),
                children
        );
    }

    private static JsonObject nested(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank() && !ANALYZE_KEYS.contains(key)) {
            map.put(key, value);
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || ANALYZE_KEYS.contains(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static BigDecimal firstDecimal(JsonObject object, String... keys) {
        for (String key : keys) {
            BigDecimal value = decimal(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        if (object == null || ANALYZE_KEYS.contains(key) || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            JsonElement element = object.get(key);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                return element.getAsBigDecimal();
            }
            String text = element.getAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            return new BigDecimal(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record ParseResult(List<PlanNode> roots, List<PlanDiagnostic> warnings, boolean structured) {
        static ParseResult failed(List<PlanDiagnostic> warnings, PlanDiagnostic error) {
            List<PlanDiagnostic> all = new ArrayList<>(warnings);
            all.add(error);
            return new ParseResult(List.of(), all, false);
        }
    }

    private static final class LimitReachedException extends RuntimeException {
        private LimitReachedException(String message) {
            super(message);
        }
    }
}
