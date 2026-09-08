package org.jumpserver.chen.modules.mysql;

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
import java.util.concurrent.atomic.AtomicInteger;

public final class MysqlPlanParser {
    public static final String V1 = "mysql-explain-json-v1";
    public static final String V2 = "mysql-explain-json-v2";
    public static final String ROWS_EXAMINED = "rows examined per scan";
    public static final String ROWS_PRODUCED = "rows produced per join";
    public static final String ROWS_ESTIMATED = "MySQL estimated rows";
    public static final String COST_PREFIX = "MySQL prefix cost";
    public static final String COST_QUERY = "MySQL query cost";
    public static final String COST_TOTAL = "MySQL estimated total cost";

    private MysqlPlanParser() {
    }

    public static ParseResult parse(String rawJson, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty MySQL plan JSON"), null);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(rawJson);
        } catch (RuntimeException e) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "MySQL plan JSON is not valid"), null);
        }
        JsonObject planRoot = unwrapPlanRoot(root);
        String version = detectVersion(planRoot);
        if (version == null) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Unrecognized MySQL EXPLAIN JSON shape"), null);
        }
        AtomicInteger ids = new AtomicInteger(1);
        AtomicInteger nodeCount = new AtomicInteger(0);
        List<PlanNode> roots = new ArrayList<>();
        try {
            if (V1.equals(version)) {
                for (JsonObject block : queryBlocks(planRoot)) {
                    PlanNode node = convertV1(block, "query_block", ids, nodeCount, 1, maxNodes, maxDepth);
                    if (node != null) {
                        roots.add(node);
                    }
                }
            } else {
                PlanNode node = convertV2(planRoot, ids, nodeCount, 1, maxNodes, maxDepth);
                if (node != null) {
                    roots.add(node);
                }
            }
        } catch (LimitReachedException e) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, e.getMessage()));
        }
        if (roots.isEmpty()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "MySQL plan JSON produced no nodes"), version);
        }
        return new ParseResult(roots, warnings, true, version);
    }

    static JsonObject unwrapPlanRoot(JsonElement root) {
        if (root != null && root.isJsonArray() && !root.getAsJsonArray().isEmpty()) {
            root = root.getAsJsonArray().get(0);
        }
        JsonObject object = asObject(root);
        if (object == null) {
            return null;
        }
        if (object.has("query_block") || object.has("operation") || object.has("inputs")) {
            return object;
        }
        for (String key : List.of("query_plan", "plan", "EXPLAIN")) {
            JsonElement nested = object.get(key);
            if (nested != null) {
                JsonObject inner = unwrapPlanRoot(nested);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return object;
    }

    static String detectVersion(JsonElement root) {
        JsonObject object = unwrapPlanRoot(root);
        if (object == null) {
            return null;
        }
        if (object.has("query_block")) {
            return V1;
        }
        if (object.has("operation") || object.has("inputs")) {
            return V2;
        }
        return null;
    }

    private static List<JsonObject> queryBlocks(JsonElement root) {
        List<JsonObject> blocks = new ArrayList<>();
        JsonObject object = unwrapPlanRoot(root);
        if (object == null) {
            return blocks;
        }
        JsonElement block = object.get("query_block");
        if (block != null && block.isJsonObject()) {
            blocks.add(block.getAsJsonObject());
        }
        return blocks;
    }

    private static PlanNode convertV1(
            JsonObject object,
            String nativeOperator,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth
    ) {
        if (nodeCount.incrementAndGet() > maxNodes || depth > maxDepth) {
            throw new LimitReachedException("Execution plan exceeded size limits");
        }
        JsonObject table = nestedObject(object, "table");
        JsonObject costInfo = nestedObject(object, "cost_info");
        if (table != null && !"table".equals(nativeOperator) && !hasOperationWrapper(object)) {
            List<PlanNode> children = new ArrayList<>();
            children.add(convertV1(table, "table", ids, nodeCount, depth + 1, maxNodes, maxDepth));
            addV1Children(object, ids, nodeCount, depth, maxNodes, maxDepth, children, true);
            return node(
                    ids,
                    nativeOperator,
                    mapNodeType(nativeOperator),
                    text(object, "message"),
                    null,
                    null,
                    null,
                    decimal(costInfo, "query_cost"),
                    COST_QUERY,
                    predicates(object),
                    attributes(object),
                    children
            );
        }
        if (table != null && "table".equals(nativeOperator)) {
            object = table;
        }
        String tableName = text(object, "table_name");
        String access = text(object, "access_type");
        String operator = access != null ? access : nativeOperator;
        BigDecimal produced = decimal(object, "rows_produced_per_join");
        BigDecimal examined = decimal(object, "rows_examined_per_scan");
        BigDecimal rows = produced != null ? produced : examined;
        String rowsMeaning = produced != null ? ROWS_PRODUCED : (examined != null ? ROWS_EXAMINED : null);
        JsonObject tableCost = nestedObject(object, "cost_info");
        BigDecimal cost = decimal(tableCost, "prefix_cost");
        if (cost == null) {
            cost = decimal(costInfo, "query_cost");
        }
        String costMeaning = tableCost != null && tableCost.has("prefix_cost") ? COST_PREFIX : COST_QUERY;
        List<PlanNode> children = new ArrayList<>();
        addV1Children(object, ids, nodeCount, depth, maxNodes, maxDepth, children, false);
        Map<String, String> predicates = predicates(object);
        putPredicate(predicates, "attached_condition", text(object, "attached_condition"));
        putPredicate(predicates, "using_condition", text(object, "used_columns"));
        return node(
                ids,
                operator,
                mapAccess(access, nativeOperator),
                text(object, "message"),
                tableName,
                rows,
                rowsMeaning,
                cost,
                costMeaning,
                predicates,
                attributes(object),
                children
        );
    }

    private static boolean hasOperationWrapper(JsonObject object) {
        return object.has("ordering_operation")
                || object.has("grouping_operation")
                || object.has("duplicates_removal")
                || object.has("nested_loop")
                || object.has("union_result");
    }

    private static void addV1Children(
            JsonObject object,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanNode> children,
            boolean skipTable
    ) {
        addNamedChild(object, "ordering_operation", "ordering_operation", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "grouping_operation", "grouping_operation", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "duplicates_removal", "duplicates_removal", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "buffer_result", "buffer_result", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "materialized_from_subquery", "materialized_from_subquery", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "union_result", "union_result", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addNamedChild(object, "windowing", "windowing", ids, nodeCount, depth, maxNodes, maxDepth, children);
        if (!skipTable) {
            addNamedChild(object, "table", "table", ids, nodeCount, depth, maxNodes, maxDepth, children);
        }
        JsonElement nested = object.get("nested_loop");
        if (nested != null && nested.isJsonArray()) {
            for (JsonElement item : nested.getAsJsonArray()) {
                JsonObject child = asObject(item);
                if (child == null) {
                    continue;
                }
                JsonObject nestedTable = nestedObject(child, "table");
                children.add(convertV1(
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
        addArrayChildren(object, "attached_subqueries", "attached_subquery", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addArrayChildren(object, "select_list_subqueries", "select_list_subquery", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addArrayChildren(object, "query_specifications", "query_specification", ids, nodeCount, depth, maxNodes, maxDepth, children);
    }

    private static void addNamedChild(
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
        JsonObject child = nestedObject(object, key);
        if (child != null) {
            children.add(convertV1(child, operator, ids, nodeCount, depth + 1, maxNodes, maxDepth));
        }
    }

    private static void addArrayChildren(
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
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            JsonObject child = asObject(item);
            if (child == null) {
                continue;
            }
            JsonObject block = nestedObject(child, "query_block");
            children.add(convertV1(block != null ? block : child, operator, ids, nodeCount, depth + 1, maxNodes, maxDepth));
        }
    }

    private static PlanNode convertV2(
            JsonObject object,
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
        String operation = text(object, "operation");
        if (operation == null) {
            operation = text(object, "query_type");
        }
        if (operation == null) {
            operation = object.has("inputs") ? "query" : "operation";
        }
        String access = text(object, "access_type");
        List<PlanNode> children = new ArrayList<>();
        addV2Inputs(object, "inputs", ids, nodeCount, depth, maxNodes, maxDepth, children);
        addV2Inputs(object, "inputs_from_select_list", ids, nodeCount, depth, maxNodes, maxDepth, children);
        Map<String, String> predicates = new LinkedHashMap<>();
        putPredicate(predicates, "condition", text(object, "condition"));
        putPredicate(predicates, "lookup_condition", text(object, "lookup_condition"));
        putPredicate(predicates, "hash_condition", text(object, "hash_condition"));
        Map<String, String> attributes = attributes(object);
        putPredicate(attributes, "join_type", text(object, "join_type"));
        putPredicate(attributes, "join_algorithm", text(object, "join_algorithm"));
        putPredicate(attributes, "heading", text(object, "heading"));
        putPredicate(attributes, "index_name", text(object, "index_name"));
        putPredicate(attributes, "sort_fields", text(object, "sort_fields"));
        if (object.has("cte")) {
            putPredicate(attributes, "cte", text(object, "cte") == null ? "true" : text(object, "cte"));
        }
        if (object.has("recursive")) {
            putPredicate(attributes, "recursive", text(object, "recursive") == null ? "true" : text(object, "recursive"));
        }
        String logical = text(object, "join_type");
        PlanNode created = node(
                ids,
                operation,
                mapAccess(access, operation),
                text(object, "access_type"),
                text(object, "table_name"),
                decimal(object, "estimated_rows"),
                decimal(object, "estimated_rows") != null ? ROWS_ESTIMATED : null,
                decimal(object, "estimated_total_cost"),
                decimal(object, "estimated_total_cost") != null ? COST_TOTAL : null,
                predicates,
                attributes,
                children
        );
        if (logical == null) {
            return created;
        }
        return new PlanNode(
                created.id(),
                created.nativeId(),
                created.nodeType(),
                created.nativeOperator(),
                logical,
                created.physicalOperator(),
                created.detail(),
                created.table(),
                created.relation(),
                created.rows(),
                created.cost(),
                created.startupCost(),
                created.rowsMeaning(),
                created.costMeaning(),
                created.predicates(),
                created.attributes(),
                created.children()
        );
    }

    static NormalizedNodeType mapAccess(String access, String nativeOperator) {
        String value = (access != null ? access : nativeOperator);
        if (value == null) {
            return NormalizedNodeType.OTHER;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("nested") || lower.contains("nlj")) {
            return NormalizedNodeType.NESTED_LOOP;
        }
        if (lower.contains("hash join") || lower.equals("hash")) {
            return NormalizedNodeType.HASH_JOIN;
        }
        if (lower.contains("index") && lower.contains("merge")) {
            return NormalizedNodeType.MERGE_JOIN;
        }
        if (lower.contains("eq_ref") || lower.contains("ref") || lower.contains("range") || lower.contains("index")) {
            return NormalizedNodeType.INDEX_SCAN;
        }
        if (lower.contains("scan") || lower.equals("all") || lower.equals("const") || lower.equals("system")) {
            return NormalizedNodeType.SCAN;
        }
        if (lower.contains("sort") || lower.contains("filesort") || lower.contains("ordering")) {
            return NormalizedNodeType.SORT;
        }
        if (lower.contains("group") || lower.contains("aggregate")) {
            return NormalizedNodeType.AGGREGATE;
        }
        if (lower.contains("union")) {
            return NormalizedNodeType.UNION;
        }
        if (lower.contains("materializ") || lower.contains("temporary")) {
            return NormalizedNodeType.MATERIALIZE;
        }
        if (lower.contains("filter")) {
            return NormalizedNodeType.FILTER;
        }
        if (lower.contains("subquery") || lower.contains("query_block")) {
            return NormalizedNodeType.SUBQUERY;
        }
        if (lower.contains("join")) {
            return NormalizedNodeType.JOIN;
        }
        if (lower.contains("limit")) {
            return NormalizedNodeType.LIMIT;
        }
        if (lower.contains("window")) {
            return NormalizedNodeType.AGGREGATE;
        }
        return NormalizedNodeType.OTHER;
    }

    static NormalizedNodeType mapNodeType(String nativeOperator) {
        return mapAccess(null, nativeOperator);
    }

    private static void addV2Inputs(
            JsonObject object,
            String key,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanNode> children
    ) {
        JsonElement inputs = object.get(key);
        if (inputs == null || !inputs.isJsonArray()) {
            return;
        }
        for (JsonElement item : inputs.getAsJsonArray()) {
            PlanNode child = convertV2(asObject(item), ids, nodeCount, depth + 1, maxNodes, maxDepth);
            if (child != null) {
                children.add(child);
            }
        }
    }

    private static PlanNode node(
            AtomicInteger ids,
            String nativeOperator,
            NormalizedNodeType type,
            String detail,
            String table,
            BigDecimal rows,
            String rowsMeaning,
            BigDecimal cost,
            String costMeaning,
            Map<String, String> predicates,
            Map<String, String> attributes,
            List<PlanNode> children
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
                rowsMeaning,
                costMeaning,
                predicates,
                attributes,
                children
        );
    }

    private static Map<String, String> predicates(JsonObject object) {
        Map<String, String> predicates = new LinkedHashMap<>();
        putPredicate(predicates, "attached_condition", text(object, "attached_condition"));
        putPredicate(predicates, "message", text(object, "message"));
        return predicates;
    }

    private static Map<String, String> attributes(JsonObject object) {
        Map<String, String> attributes = new LinkedHashMap<>();
        putPredicate(attributes, "access_type", text(object, "access_type"));
        putPredicate(attributes, "key", text(object, "key"));
        putPredicate(attributes, "possible_keys", text(object, "possible_keys"));
        putPredicate(attributes, "using_filesort", text(object, "using_filesort"));
        putPredicate(attributes, "using_temporary_table", text(object, "using_temporary_table"));
        putPredicate(attributes, "select_id", text(object, "select_id"));
        return attributes;
    }

    private static void putPredicate(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static JsonObject nestedObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject asObject(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            return value == null || value.isBlank() ? null : value;
        }
        if (element.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    parts.add(item.getAsString());
                }
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        return element.toString();
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
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

    public record ParseResult(List<PlanNode> roots, List<PlanDiagnostic> warnings, boolean structured, String rawFormatVersion) {
        static ParseResult failed(List<PlanDiagnostic> warnings, PlanDiagnostic error, String version) {
            List<PlanDiagnostic> all = new ArrayList<>(warnings);
            all.add(error);
            return new ParseResult(List.of(), all, false, version);
        }
    }

    private static final class LimitReachedException extends RuntimeException {
        private LimitReachedException(String message) {
            super(message);
        }
    }
}
