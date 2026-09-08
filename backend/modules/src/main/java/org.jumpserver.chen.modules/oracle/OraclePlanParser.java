package org.jumpserver.chen.modules.oracle;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class OraclePlanParser {
    public static final String ROWS_MEANING = "Oracle estimated output cardinality";
    public static final String COST_MEANING = "Oracle optimizer estimated subtree cost";

    private static final Set<String> NUMERIC_COLUMNS = Set.of(
            "ID", "PARENT_ID", "DEPTH", "POSITION", "COST", "CARDINALITY", "BYTES",
            "CPU_COST", "IO_COST", "TEMP_SPACE", "TIME", "SEARCH_COLUMNS", "OBJECT_INSTANCE"
    );

    private OraclePlanParser() {
    }

    public static ParseResult parse(String rawTableJson, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawTableJson == null || rawTableJson.isBlank()) {
            return ParseResult.failed(warnings, "Empty Oracle plan table result");
        }

        JsonObject envelope;
        try {
            JsonElement parsed = JsonParser.parseString(rawTableJson);
            if (!parsed.isJsonObject()) {
                return ParseResult.failed(warnings, "Oracle plan table envelope is not an object");
            }
            envelope = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return ParseResult.failed(warnings, "Oracle plan table envelope is not valid JSON");
        }

        List<Row> rows;
        try {
            rows = rows(envelope);
        } catch (IllegalArgumentException e) {
            return ParseResult.failed(warnings, e.getMessage());
        }
        if (rows.isEmpty()) {
            return ParseResult.failed(warnings, "Oracle PLAN_TABLE returned no rows for this request");
        }
        if (rows.size() > maxNodes) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Execution plan exceeded the node limit"));
            return new ParseResult(List.of(), warnings, false);
        }

        Map<String, Row> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            String id = row.text("ID");
            if (id == null) {
                return ParseResult.failed(warnings, "Oracle PLAN_TABLE row has no ID");
            }
            if (byId.putIfAbsent(id, row) != null) {
                return ParseResult.failed(warnings, "Oracle PLAN_TABLE contains duplicate ID " + id);
            }
        }

        Map<String, List<Row>> childrenByParent = new HashMap<>();
        List<Row> roots = new ArrayList<>();
        for (Row row : rows) {
            String parentId = row.text("PARENT_ID");
            if (parentId == null) {
                roots.add(row);
            } else if (byId.containsKey(parentId)) {
                childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(row);
            } else {
                roots.add(row);
                warnings.add(PlanDiagnostic.of(
                        PlanCodes.PLAN_PARSE_FAILED,
                        "Oracle plan node " + row.text("ID") + " references missing parent " + parentId
                ));
            }
        }

        Comparator<Row> order = Comparator
                .comparing((Row row) -> row.decimal("POSITION"), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.decimal("ID"), Comparator.nullsLast(Comparator.naturalOrder()));
        roots.sort(order);
        childrenByParent.values().forEach(children -> children.sort(order));

        AtomicInteger displayIds = new AtomicInteger(1);
        AtomicInteger nodeCount = new AtomicInteger();
        List<PlanNode> parsedRoots = new ArrayList<>();
        for (Row root : roots) {
            PlanNode node = convert(
                    root,
                    childrenByParent,
                    displayIds,
                    nodeCount,
                    1,
                    maxNodes,
                    maxDepth,
                    new HashSet<>(),
                    warnings
            );
            if (node != null) {
                parsedRoots.add(node);
            }
        }
        boolean incomplete = warnings.stream().anyMatch(warning ->
                PlanCodes.PLAN_LIMIT_REACHED.equals(warning.code())
                        || PlanCodes.PLAN_PARSE_FAILED.equals(warning.code()));
        if (incomplete) {
            return new ParseResult(List.of(), warnings, false);
        }
        if (parsedRoots.isEmpty()) {
            return ParseResult.failed(warnings, "Oracle PLAN_TABLE produced no plan roots");
        }
        return new ParseResult(parsedRoots, warnings, true);
    }

    private static List<Row> rows(JsonObject envelope) {
        JsonArray resultSets = array(envelope, "resultSets");
        if (resultSets.isEmpty() || !resultSets.get(0).isJsonObject()) {
            throw new IllegalArgumentException("Oracle plan table envelope has no result set");
        }
        JsonObject resultSet = resultSets.get(0).getAsJsonObject();
        JsonArray columns = array(resultSet, "columns");
        JsonArray values = array(resultSet, "rows");
        List<String> names = new ArrayList<>();
        for (JsonElement element : columns) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Oracle plan table column metadata is invalid");
            }
            String name = text(element.getAsJsonObject().get("name"));
            if (name == null) {
                throw new IllegalArgumentException("Oracle plan table column has no name");
            }
            names.add(name.toUpperCase(Locale.ROOT));
        }

        List<Row> result = new ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonArray()) {
                throw new IllegalArgumentException("Oracle plan table row is invalid");
            }
            JsonArray rowValues = element.getAsJsonArray();
            if (rowValues.size() != names.size()) {
                throw new IllegalArgumentException("Oracle plan table row width does not match its columns");
            }
            Map<String, JsonElement> fields = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                fields.put(names.get(i), rowValues.get(i));
            }
            result.add(new Row(fields));
        }
        return result;
    }

    private static PlanNode convert(
            Row row,
            Map<String, List<Row>> childrenByParent,
            AtomicInteger displayIds,
            AtomicInteger nodeCount,
            int depth,
            int maxNodes,
            int maxDepth,
            Set<String> path,
            List<PlanDiagnostic> warnings
    ) {
        if (nodeCount.incrementAndGet() > maxNodes) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Execution plan exceeded the node limit"));
            return null;
        }
        if (depth > maxDepth) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "Execution plan exceeded the depth limit"));
            return null;
        }

        String nativeId = row.text("ID");
        if (!path.add(nativeId)) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Oracle plan contains a cycle at node " + nativeId));
            return null;
        }

        String operation = defaultText(row.text("OPERATION"), "Unknown");
        String options = row.text("OPTIONS");
        String nativeOperator = options == null ? operation : operation + " " + options;
        String owner = row.text("OBJECT_OWNER");
        String table = row.text("OBJECT_NAME");
        String relation = table == null ? null : owner == null ? table : owner + "." + table;

        Map<String, String> predicates = new LinkedHashMap<>();
        put(predicates, "Access Predicates", row.text("ACCESS_PREDICATES"));
        put(predicates, "Filter Predicates", row.text("FILTER_PREDICATES"));

        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "Options", options);
        put(attributes, "Object Owner", owner);
        put(attributes, "Object Name", table);
        put(attributes, "Object Alias", row.text("OBJECT_ALIAS"));
        put(attributes, "Object Type", row.text("OBJECT_TYPE"));
        put(attributes, "Optimizer", row.text("OPTIMIZER"));
        put(attributes, "Bytes", row.text("BYTES"));
        put(attributes, "CPU Cost", row.text("CPU_COST"));
        put(attributes, "IO Cost", row.text("IO_COST"));
        put(attributes, "Temp Space", row.text("TEMP_SPACE"));
        put(attributes, "Projection", row.text("PROJECTION"));
        put(attributes, "Time", row.text("TIME"));
        put(attributes, "Query Block", row.text("QBLOCK_NAME"));
        put(attributes, "Other Tag", row.text("OTHER_TAG"));
        put(attributes, "Partition Start", row.text("PARTITION_START"));
        put(attributes, "Partition Stop", row.text("PARTITION_STOP"));
        put(attributes, "Depth", row.text("DEPTH"));
        put(attributes, "Position", row.text("POSITION"));

        List<PlanNode> children = new ArrayList<>();
        for (Row child : childrenByParent.getOrDefault(nativeId, List.of())) {
            PlanNode parsed = convert(
                    child,
                    childrenByParent,
                    displayIds,
                    nodeCount,
                    depth + 1,
                    maxNodes,
                    maxDepth,
                    new HashSet<>(path),
                    warnings
            );
            if (parsed != null) {
                children.add(parsed);
            }
        }

        return new PlanNode(
                "n" + displayIds.getAndIncrement(),
                nativeId,
                mapNodeType(nativeOperator),
                nativeOperator,
                logicalOperator(operation, options),
                operation,
                null,
                table,
                relation,
                row.decimal("CARDINALITY"),
                row.decimal("COST"),
                null,
                ROWS_MEANING,
                COST_MEANING,
                predicates,
                attributes,
                children
        );
    }

    static NormalizedNodeType mapNodeType(String nativeOperator) {
        String value = nativeOperator == null ? "" : nativeOperator.toLowerCase(Locale.ROOT);
        if (value.contains("index fast full scan") || value.contains("index full scan")) {
            return NormalizedNodeType.INDEX_ONLY_SCAN;
        }
        if (value.contains("index") && value.contains("scan")) {
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
        if (value.contains("table access") || value.contains("mat_view access")
                || value.contains("collection iterator") || value.contains("cluster scan")) {
            return NormalizedNodeType.SCAN;
        }
        if (value.startsWith("sort")) {
            return NormalizedNodeType.SORT;
        }
        if (value.contains("group by") || value.contains("aggregate") || value.startsWith("window")) {
            return NormalizedNodeType.AGGREGATE;
        }
        if (value.contains("stopkey") || value.startsWith("first rows")) {
            return NormalizedNodeType.LIMIT;
        }
        if (value.startsWith("px ") || value.contains("parallel")) {
            return NormalizedNodeType.PARALLEL;
        }
        if (value.contains("union")) {
            return NormalizedNodeType.UNION;
        }
        if (value.contains("temp table transformation") || value.contains("materialize")) {
            return NormalizedNodeType.MATERIALIZE;
        }
        if (value.equals("view") || value.contains("subquery")) {
            return NormalizedNodeType.SUBQUERY;
        }
        if (value.equals("hash") || value.startsWith("hash ")) {
            return NormalizedNodeType.HASH;
        }
        if (value.contains("scan") || value.contains("access")) {
            return NormalizedNodeType.SCAN;
        }
        if (value.contains("projection") || value.equals("select statement")) {
            return NormalizedNodeType.PROJECTION;
        }
        return NormalizedNodeType.OTHER;
    }

    private static String logicalOperator(String operation, String options) {
        if (operation == null || !operation.toLowerCase(Locale.ROOT).contains("join")) {
            return null;
        }
        return options == null ? "Join" : options;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return new JsonArray();
        }
        return value.getAsJsonArray();
    }

    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text == null || text.isBlank() ? null : text;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private record Row(Map<String, JsonElement> fields) {
        String text(String name) {
            return OraclePlanParser.text(fields.get(name));
        }

        BigDecimal decimal(String name) {
            JsonElement value = fields.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                return null;
            }
            try {
                return value.getAsBigDecimal();
            } catch (RuntimeException e) {
                if (!NUMERIC_COLUMNS.contains(name)) {
                    return null;
                }
                try {
                    return new BigDecimal(value.getAsString());
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
    }

    public record ParseResult(List<PlanNode> roots, List<PlanDiagnostic> warnings, boolean structured) {
        public ParseResult {
            roots = roots == null ? List.of() : List.copyOf(roots);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        static ParseResult failed(List<PlanDiagnostic> warnings, String message) {
            List<PlanDiagnostic> result = new ArrayList<>(warnings);
            result.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, message));
            return new ParseResult(List.of(), result, false);
        }
    }
}
