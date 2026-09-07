package org.jumpserver.chen.modules.postgresql;

import com.google.gson.JsonArray;
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

public final class PostgresqlPlanParser {
    public static final String ROWS_MEANING = "output rows per invocation";
    public static final String COST_MEANING = "PostgreSQL total subtree cost";
    private static final String[] PREDICATE_KEYS = {
            "Filter",
            "Index Cond",
            "Recheck Cond",
            "Join Filter",
            "Hash Cond",
            "Merge Cond",
            "TID Cond",
            "Cache Key",
            "One-Time Filter"
    };

    private PostgresqlPlanParser() {
    }

    public static ParseResult parse(String rawJson, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty PostgreSQL plan JSON"));
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(rawJson);
        } catch (RuntimeException e) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "PostgreSQL plan JSON is not valid"));
        }

        List<JsonObject> planObjects = extractPlans(root);
        if (planObjects.isEmpty()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "PostgreSQL plan JSON has no Plan node"));
        }

        AtomicInteger ids = new AtomicInteger(1);
        AtomicInteger nodeCount = new AtomicInteger(0);
        List<PlanNode> roots = new ArrayList<>();
        try {
            for (JsonObject planObject : planObjects) {
                PlanNode node = convert(planObject, ids, nodeCount, 1, maxNodes, maxDepth, warnings);
                if (node != null) {
                    roots.add(node);
                }
            }
        } catch (LimitReachedException e) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, e.getMessage()));
        }

        if (roots.isEmpty()) {
            return ParseResult.failed(warnings, PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "PostgreSQL plan JSON produced no nodes"));
        }
        return new ParseResult(roots, warnings, true);
    }

    private static List<JsonObject> extractPlans(JsonElement root) {
        List<JsonObject> plans = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                collectPlan(element, plans);
            }
            return plans;
        }
        collectPlan(root, plans);
        return plans;
    }

    private static void collectPlan(JsonElement element, List<JsonObject> plans) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("Plan") && object.get("Plan").isJsonObject()) {
            plans.add(object.getAsJsonObject("Plan"));
            return;
        }
        if (object.has("Node Type")) {
            plans.add(object);
        }
    }

    private static PlanNode convert(
            JsonObject plan,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanDiagnostic> warnings
    ) {
        if (nodeCount.incrementAndGet() > maxNodes) {
            throw new LimitReachedException("Execution plan exceeded the node limit");
        }
        if (depth > maxDepth) {
            throw new LimitReachedException("Execution plan exceeded the depth limit");
        }

        String nativeOperator = text(plan, "Node Type");
        if (nativeOperator == null || nativeOperator.isBlank()) {
            nativeOperator = "Unknown";
        }
        String schema = text(plan, "Schema");
        String relationName = text(plan, "Relation Name");
        String relation = relationName;
        if (schema != null && !schema.isBlank() && relationName != null) {
            relation = schema + "." + relationName;
        }

        Map<String, String> predicates = new LinkedHashMap<>();
        for (String key : PREDICATE_KEYS) {
            String value = text(plan, key);
            if (value != null) {
                predicates.put(key, value);
            }
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        putAttr(attributes, "Alias", text(plan, "Alias"));
        putAttr(attributes, "Parent Relationship", text(plan, "Parent Relationship"));
        putAttr(attributes, "Subplan Name", text(plan, "Subplan Name"));
        putAttr(attributes, "Index Name", text(plan, "Index Name"));
        putAttr(attributes, "Strategy", text(plan, "Strategy"));
        putAttr(attributes, "Parallel Aware", text(plan, "Parallel Aware"));
        putAttr(attributes, "Output", text(plan, "Output"));

        List<PlanNode> children = new ArrayList<>();
        addChildren(plan.get("Plans"), ids, nodeCount, depth, maxNodes, maxDepth, warnings, children);
        addChildren(plan.get("Subplans"), ids, nodeCount, depth, maxNodes, maxDepth, warnings, children);
        addChildren(plan.get("InitPlan"), ids, nodeCount, depth, maxNodes, maxDepth, warnings, children);

        String id = "n" + ids.getAndIncrement();
        String detail = firstNonBlank(text(plan, "Subplan Name"), predicates.get("Filter"), relation);

        return new PlanNode(
                id,
                text(plan, "Node Id"),
                mapNodeType(nativeOperator),
                nativeOperator,
                nativeOperator,
                nativeOperator,
                detail,
                relationName,
                relation,
                decimal(plan, "Plan Rows"),
                decimal(plan, "Total Cost"),
                decimal(plan, "Startup Cost"),
                ROWS_MEANING,
                COST_MEANING,
                predicates,
                attributes,
                children
        );
    }

    private static void addChildren(
            JsonElement element,
            AtomicInteger ids,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanDiagnostic> warnings,
            List<PlanNode> children
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonObject plan = object.has("Plan") && object.get("Plan").isJsonObject()
                    ? object.getAsJsonObject("Plan")
                    : object;
            PlanNode child = convert(plan, ids, nodeCount, depth + 1, maxNodes, maxDepth, warnings);
            if (child != null) {
                children.add(child);
            }
            return;
        }
        if (!element.isJsonArray()) {
            return;
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement childElement : array) {
            if (childElement == null || childElement.isJsonNull()) {
                continue;
            }
            if (childElement.isJsonObject()) {
                addChildren(childElement, ids, nodeCount, depth, maxNodes, maxDepth, warnings, children);
            }
        }
    }

    static NormalizedNodeType mapNodeType(String nativeOperator) {
        String value = nativeOperator == null ? "" : nativeOperator.toLowerCase(Locale.ROOT);
        if (value.contains("index only scan")) {
            return NormalizedNodeType.INDEX_ONLY_SCAN;
        }
        if (value.contains("index scan") || value.contains("bitmap")) {
            return NormalizedNodeType.INDEX_SCAN;
        }
        if (value.contains("hash join")) {
            return NormalizedNodeType.HASH_JOIN;
        }
        if (value.contains("merge join")) {
            return NormalizedNodeType.MERGE_JOIN;
        }
        if (value.contains("nested loop")) {
            return NormalizedNodeType.NESTED_LOOP;
        }
        if (value.contains("join")) {
            return NormalizedNodeType.JOIN;
        }
        if (value.contains("seq scan") || value.contains("foreign scan") || value.contains("function scan")
                || value.contains("table function") || value.contains("worktable scan")
                || value.contains("cte scan") && !value.contains("subquery")) {
            return value.contains("cte scan") ? NormalizedNodeType.SUBQUERY : NormalizedNodeType.SCAN;
        }
        if (value.contains("subquery") || value.contains("cte scan") || value.contains("initplan") || value.contains("subplan")) {
            return NormalizedNodeType.SUBQUERY;
        }
        if (value.equals("hash") || value.startsWith("hash ")) {
            return NormalizedNodeType.HASH;
        }
        if (value.contains("sort")) {
            return NormalizedNodeType.SORT;
        }
        if (value.contains("aggregate") || value.contains("group") || value.contains("window")) {
            return NormalizedNodeType.AGGREGATE;
        }
        if (value.contains("limit")) {
            return NormalizedNodeType.LIMIT;
        }
        if (value.contains("gather") || value.contains("parallel")) {
            return NormalizedNodeType.PARALLEL;
        }
        if (value.contains("append") || value.contains("union")) {
            return NormalizedNodeType.UNION;
        }
        if (value.contains("materialize")) {
            return NormalizedNodeType.MATERIALIZE;
        }
        if (value.contains("result") || value.contains("values")) {
            return NormalizedNodeType.VALUES;
        }
        if (value.contains("project") || value.contains("result")) {
            return NormalizedNodeType.PROJECTION;
        }
        if (value.contains("scan")) {
            return NormalizedNodeType.SCAN;
        }
        return NormalizedNodeType.OTHER;
    }

    private static void putAttr(Map<String, String> attributes, String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
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
        public static ParseResult failed(List<PlanDiagnostic> warnings, PlanDiagnostic error) {
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
