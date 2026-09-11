package org.jumpserver.chen.modules.clickhouse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jumpserver.chen.framework.datasource.plan.NormalizedNodeType;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure parser for ClickHouse EXPLAIN PLAN json=1 output. */
public final class ClickHousePlanParser {
    public static final String JSON_FORMAT_VERSION = "clickhouse-plan-json";
    public static final String TEXT_FORMAT_VERSION = "clickhouse-plan-text";

    private ClickHousePlanParser() {
    }

    public static ParseResult parseJson(String rawJson, int maxNodes, int maxDepth) {
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.failed(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty ClickHouse PLAN JSON"));
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(rawJson);
        } catch (RuntimeException e) {
            return ParseResult.failed(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "ClickHouse PLAN JSON is not valid"));
        }
        List<JsonObject> planObjects = unwrapPlans(parsed);
        if (planObjects.isEmpty()) {
            return ParseResult.failed(PlanDiagnostic.of(
                    PlanCodes.PLAN_PARSE_FAILED,
                    "Unrecognized ClickHouse PLAN JSON shape"
            ));
        }
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        List<PlanNode> roots = new ArrayList<>();
        List<PlanDiagnostic> warnings = new ArrayList<>();
        try {
            for (JsonObject plan : planObjects) {
                roots.add(convert(plan, ids, count, 1, maxNodes, maxDepth));
            }
        } catch (LimitReachedException e) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, e.getMessage()));
        }
        if (roots.isEmpty()) {
            return new ParseResult(List.of(), warnings, false,
                    PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "ClickHouse PLAN JSON produced no nodes"));
        }
        return new ParseResult(roots, warnings, true, null);
    }

    public static ParseResult parseText(String rawText, int maxNodes, int maxDepth) {
        if (rawText == null || rawText.isBlank()) {
            return ParseResult.failed(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty ClickHouse PLAN text"));
        }
        List<TextNode> roots = new ArrayList<>();
        List<TextNode> stack = new ArrayList<>();
        int id = 0;
        int baseIndent = -1;
        List<PlanDiagnostic> warnings = new ArrayList<>();
        for (String line : rawText.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(line);
            if (baseIndent < 0) {
                baseIndent = indent;
            }
            int level = Math.max(0, (indent - baseIndent) / 2);
            if (++id > maxNodes || level + 1 > maxDepth) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "ClickHouse execution plan exceeded size limits"));
                break;
            }
            String value = line.strip();
            TextNode node = new TextNode("n" + id, value);
            while (stack.size() > level) {
                stack.remove(stack.size() - 1);
            }
            if (level > 0 && !stack.isEmpty()) {
                stack.get(stack.size() - 1).children.add(node);
            } else {
                roots.add(node);
            }
            stack.add(node);
        }
        if (roots.isEmpty()) {
            return ParseResult.failed(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Unrecognized ClickHouse PLAN text"));
        }
        return new ParseResult(roots.stream().map(TextNode::freeze).toList(), warnings, true, null);
    }

    private static List<JsonObject> unwrapPlans(JsonElement value) {
        List<JsonObject> result = new ArrayList<>();
        if (value == null || value.isJsonNull()) {
            return result;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                result.addAll(unwrapPlans(item));
            }
            return result;
        }
        if (!value.isJsonObject()) {
            return result;
        }
        JsonObject object = value.getAsJsonObject();
        JsonElement wrapper = first(object, "Plan", "plan");
        if (wrapper != null) {
            return unwrapPlans(wrapper);
        }
        if (first(object, "Node Type", "NodeType", "node_type") != null) {
            result.add(object);
        }
        return result;
    }

    private static PlanNode convert(
            JsonObject object,
            AtomicInteger ids,
            AtomicInteger count,
            int depth,
            int maxNodes,
            int maxDepth
    ) {
        if (count.incrementAndGet() > maxNodes || depth > maxDepth) {
            throw new LimitReachedException("ClickHouse execution plan exceeded size limits");
        }
        String operator = text(first(object, "Node Type", "NodeType", "node_type"));
        if (operator == null || operator.isBlank()) {
            operator = "Unknown PLAN node";
        }
        List<PlanNode> children = new ArrayList<>();
        JsonElement plans = first(object, "Plans", "plans");
        if (plans != null && plans.isJsonArray()) {
            for (JsonElement child : plans.getAsJsonArray()) {
                if (child.isJsonObject()) {
                    children.add(convert(child.getAsJsonObject(), ids, count, depth + 1, maxNodes, maxDepth));
                }
            }
        }
        return new PlanNode(
                "n" + ids.incrementAndGet(), text(first(object, "Node Id", "NodeId", "node_id")),
                mapNodeType(operator), operator, null, operator,
                text(first(object, "Description", "description")), null, null,
                null, null, null, null, null, Map.of(), Map.of(), children
        );
    }

    static NormalizedNodeType mapNodeType(String operator) {
        String value = operator == null ? "" : operator.toLowerCase(Locale.ROOT);
        if (value.contains("join")) return value.contains("hash") ? NormalizedNodeType.HASH_JOIN : NormalizedNodeType.JOIN;
        if (value.contains("read") || value.contains("scan")) return NormalizedNodeType.SCAN;
        if (value.startsWith("expression") || value.contains("project")) return NormalizedNodeType.PROJECTION;
        if (value.contains("sort") || value.contains("order")) return NormalizedNodeType.SORT;
        if (value.contains("aggregat")) return NormalizedNodeType.AGGREGATE;
        if (value.contains("filter")) return NormalizedNodeType.FILTER;
        if (value.contains("limit")) return NormalizedNodeType.LIMIT;
        if (value.contains("union")) return NormalizedNodeType.UNION;
        return NormalizedNodeType.OTHER;
    }

    private static JsonElement first(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name)) return object.get(name);
        }
        return null;
    }

    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static int leadingSpaces(String value) {
        int result = 0;
        while (result < value.length() && Character.isWhitespace(value.charAt(result))) result++;
        return result;
    }

    public record ParseResult(
            List<PlanNode> roots,
            List<PlanDiagnostic> warnings,
            boolean structured,
            PlanDiagnostic error
    ) {
        public ParseResult {
            roots = roots == null ? List.of() : List.copyOf(roots);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        static ParseResult failed(PlanDiagnostic error) {
            return new ParseResult(List.of(), List.of(), false, error);
        }
    }

    private static final class TextNode {
        private final String id;
        private final String operator;
        private final List<TextNode> children = new ArrayList<>();

        private TextNode(String id, String operator) {
            this.id = id;
            this.operator = operator;
        }

        private PlanNode freeze() {
            return new PlanNode(
                    id, null, mapNodeType(operator), operator, null, operator, null,
                    null, null, null, null, null, null, null,
                    Map.of(), Map.of(), children.stream().map(TextNode::freeze).toList()
            );
        }
    }

    private static final class LimitReachedException extends RuntimeException {
        private LimitReachedException(String message) {
            super(message);
        }
    }
}
