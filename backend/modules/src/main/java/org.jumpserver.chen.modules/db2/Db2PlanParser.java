package org.jumpserver.chen.modules.db2;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Db2PlanParser {
    public static final String ROWS_MEANING = "DB2 estimated stream cardinality (STREAM_COUNT)";
    public static final String COST_MEANING = "DB2 cumulative optimizer cost in timerons (TOTAL_COST)";

    private Db2PlanParser() {
    }

    public static ParseResult parse(String rawTableJson, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawTableJson == null || rawTableJson.isBlank()) {
            return ParseResult.failed(warnings, "Empty DB2 Explain table result");
        }

        Map<String, TableData> tables;
        try {
            tables = tables(rawTableJson);
        } catch (IllegalArgumentException e) {
            return ParseResult.failed(warnings, e.getMessage());
        }
        TableData operatorsTable = tables.get("EXPLAIN_OPERATOR");
        TableData streamsTable = tables.get("EXPLAIN_STREAM");
        if (operatorsTable == null || streamsTable == null) {
            return ParseResult.failed(warnings, "DB2 Explain envelope is missing OPERATOR or STREAM data");
        }

        Map<String, Row> operators = new LinkedHashMap<>();
        for (Row row : operatorsTable.rows()) {
            String id = row.text("OPERATOR_ID");
            if (id == null) {
                return ParseResult.failed(warnings, "DB2 EXPLAIN_OPERATOR row has no OPERATOR_ID");
            }
            if (operators.putIfAbsent(id, row) != null) {
                return ParseResult.failed(warnings, "DB2 EXPLAIN_OPERATOR contains duplicate OPERATOR_ID " + id);
            }
        }
        if (operators.isEmpty()) {
            return ParseResult.failed(warnings, "DB2 EXPLAIN_OPERATOR returned no rows for this request");
        }

        List<Row> streams = streamsTable.rows();
        Map<String, List<Row>> childrenByTarget = new HashMap<>();
        Map<String, List<Row>> streamsByOperator = new HashMap<>();
        Map<String, Integer> parentCountBySource = new HashMap<>();
        Map<String, Integer> edgeCount = new HashMap<>();
        Set<String> operatorSources = new HashSet<>();
        AtomicBoolean incomplete = new AtomicBoolean(false);

        for (Row stream : streams) {
            String sourceType = upper(stream.text("SOURCE_TYPE"));
            String targetType = upper(stream.text("TARGET_TYPE"));
            String sourceId = stream.text("SOURCE_ID");
            String targetId = stream.text("TARGET_ID");
            if ("O".equals(sourceType) && sourceId != null) {
                streamsByOperator.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(stream);
            }
            if ("O".equals(targetType) && targetId != null) {
                streamsByOperator.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(stream);
            }
            if ("O".equals(sourceType) && "O".equals(targetType)) {
                if (!operators.containsKey(sourceId) || !operators.containsKey(targetId)) {
                    warnings.add(PlanDiagnostic.of(
                            PlanCodes.PLAN_PARSE_FAILED,
                            "DB2 stream " + defaultText(stream.text("STREAM_ID"), "?")
                                    + " references a missing operator"
                    ));
                    incomplete.set(true);
                    continue;
                }
                childrenByTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(stream);
                operatorSources.add(sourceId);
                parentCountBySource.merge(sourceId, 1, Integer::sum);
                edgeCount.merge(sourceId + "->" + targetId, 1, Integer::sum);
            }
        }

        Comparator<Row> streamOrder = Comparator
                .comparing((Row row) -> row.decimal("STREAM_ID"), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> defaultText(row.text("SOURCE_ID"), ""));
        childrenByTarget.values().forEach(rows -> rows.sort(streamOrder));
        streamsByOperator.values().forEach(rows -> rows.sort(streamOrder));

        List<String> rootIds = operators.keySet().stream()
                .filter(id -> !operatorSources.contains(id))
                .sorted(Db2PlanParser::compareNativeIds)
                .toList();
        if (rootIds.isEmpty()) {
            warnings.add(PlanDiagnostic.of(
                    PlanCodes.PLAN_PARSE_FAILED,
                    "DB2 Explain operator graph has no root; a cycle is present"
            ));
            return new ParseResult(List.of(), warnings, false);
        }

        Map<String, List<Row>> predicates = group(tables.get("EXPLAIN_PREDICATE"), "OPERATOR_ID");
        Map<String, List<Row>> arguments = group(tables.get("EXPLAIN_ARGUMENT"), "OPERATOR_ID");
        Map<String, List<Row>> objects = objects(tables.get("EXPLAIN_OBJECT"));
        AtomicInteger displayIds = new AtomicInteger();
        AtomicInteger displayedNodes = new AtomicInteger();
        List<PlanNode> roots = new ArrayList<>();
        for (String rootId : rootIds) {
            PlanNode root = convert(
                    rootId,
                    null,
                    operators,
                    childrenByTarget,
                    streamsByOperator,
                    parentCountBySource,
                    edgeCount,
                    predicates,
                    arguments,
                    objects,
                    displayIds,
                    displayedNodes,
                    1,
                    maxNodes,
                    maxDepth,
                    new LinkedHashSet<>(),
                    warnings,
                    incomplete
            );
            if (root != null) {
                roots.add(root);
            }
        }

        Set<String> reachable = new HashSet<>();
        collectReachable(rootIds, childrenByTarget, reachable);
        if (!reachable.containsAll(operators.keySet())) {
            Set<String> disconnected = new LinkedHashSet<>(operators.keySet());
            disconnected.removeAll(reachable);
            warnings.add(PlanDiagnostic.of(
                    PlanCodes.PLAN_PARSE_FAILED,
                    "DB2 Explain graph has disconnected operators: " + String.join(", ", disconnected)
            ));
            incomplete.set(true);
        }
        if (incomplete.get() || roots.isEmpty()) {
            return new ParseResult(List.of(), warnings, false);
        }
        return new ParseResult(roots, warnings, true);
    }

    private static PlanNode convert(
            String nativeId,
            Row incomingEdge,
            Map<String, Row> operators,
            Map<String, List<Row>> childrenByTarget,
            Map<String, List<Row>> streamsByOperator,
            Map<String, Integer> parentCountBySource,
            Map<String, Integer> edgeCount,
            Map<String, List<Row>> predicatesByOperator,
            Map<String, List<Row>> argumentsByOperator,
            Map<String, List<Row>> objectsByName,
            AtomicInteger displayIds,
            AtomicInteger displayedNodes,
            int depth,
            int maxNodes,
            int maxDepth,
            Set<String> path,
            List<PlanDiagnostic> warnings,
            AtomicBoolean incomplete
    ) {
        if (displayedNodes.incrementAndGet() > maxNodes) {
            addLimitWarning(warnings, "DB2 execution plan exceeded the node limit");
            incomplete.set(true);
            return null;
        }
        if (depth > maxDepth) {
            addLimitWarning(warnings, "DB2 execution plan exceeded the depth limit");
            incomplete.set(true);
            return null;
        }
        if (!path.add(nativeId)) {
            warnings.add(PlanDiagnostic.of(
                    PlanCodes.PLAN_PARSE_FAILED,
                    "DB2 Explain graph contains a cycle at operator " + nativeId
            ));
            incomplete.set(true);
            return null;
        }

        Row operator = operators.get(nativeId);
        if (operator == null) {
            incomplete.set(true);
            return null;
        }
        String nativeOperator = defaultText(operator.text("OPERATOR_TYPE"), "UNKNOWN").trim();
        Map<String, String> attributes = new LinkedHashMap<>();
        copyRow(attributes, "Operator.", operator, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));

        if (incomingEdge != null) {
            copyRow(attributes, "IncomingStream.", incomingEdge, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));
            String source = incomingEdge.text("SOURCE_ID");
            String target = incomingEdge.text("TARGET_ID");
            if (parentCountBySource.getOrDefault(source, 0) > 1) {
                attributes.put("Shared Subplan", "true");
            }
            if (edgeCount.getOrDefault(source + "->" + target, 0) > 1) {
                attributes.put("Repeated Edge", "true");
            }
        }

        List<Row> relatedStreams = streamsByOperator.getOrDefault(nativeId, List.of());
        ObjectInfo objectInfo = objectInfo(nativeId, relatedStreams, objectsByName, attributes);
        Map<String, String> predicates = predicates(predicatesByOperator.getOrDefault(nativeId, List.of()), attributes);
        arguments(argumentsByOperator.getOrDefault(nativeId, List.of()), attributes);

        Row cardinalityStream = cardinalityStream(nativeId, incomingEdge, relatedStreams);
        BigDecimal rows = nonNegative(cardinalityStream == null ? null : cardinalityStream.decimal("STREAM_COUNT"));
        BigDecimal cost = nonNegative(operator.decimal("TOTAL_COST"));

        List<PlanNode> children = new ArrayList<>();
        for (Row edge : childrenByTarget.getOrDefault(nativeId, List.of())) {
            String childId = edge.text("SOURCE_ID");
            PlanNode child = convert(
                    childId,
                    edge,
                    operators,
                    childrenByTarget,
                    streamsByOperator,
                    parentCountBySource,
                    edgeCount,
                    predicatesByOperator,
                    argumentsByOperator,
                    objectsByName,
                    displayIds,
                    displayedNodes,
                    depth + 1,
                    maxNodes,
                    maxDepth,
                    new LinkedHashSet<>(path),
                    warnings,
                    incomplete
            );
            if (child != null) {
                children.add(child);
            }
        }

        return new PlanNode(
                "db2-" + displayIds.incrementAndGet(),
                nativeId,
                mapNodeType(nativeOperator),
                nativeOperator,
                null,
                nativeOperator,
                objectInfo.detail(),
                objectInfo.table(),
                objectInfo.relation(),
                rows,
                cost,
                nonNegative(operator.decimal("FIRST_ROW_COST")),
                rows == null ? null : ROWS_MEANING,
                cost == null ? null : COST_MEANING,
                predicates,
                attributes,
                children
        );
    }

    private static ObjectInfo objectInfo(
            String operatorId,
            List<Row> streams,
            Map<String, List<Row>> objects,
            Map<String, String> attributes
    ) {
        String firstTable = null;
        String firstRelation = null;
        int streamIndex = 0;
        int objectIndex = 0;
        for (Row stream : streams) {
            boolean belongs = ("D".equalsIgnoreCase(stream.text("SOURCE_TYPE"))
                    && operatorId.equals(stream.text("TARGET_ID")))
                    || ("D".equalsIgnoreCase(stream.text("TARGET_TYPE"))
                    && operatorId.equals(stream.text("SOURCE_ID")));
            if (!belongs) {
                continue;
            }
            streamIndex++;
            copyRow(attributes, "ObjectStream[" + streamIndex + "].", stream, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));
            String schema = stream.text("OBJECT_SCHEMA");
            String name = stream.text("OBJECT_NAME");
            if (name != null && firstTable == null) {
                firstTable = name;
                firstRelation = schema == null ? name : schema + "." + name;
            }
            for (Row object : objects.getOrDefault(objectKey(schema, name), List.of())) {
                objectIndex++;
                copyRow(attributes, "Object[" + objectIndex + "].", object, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));
            }
        }
        String detail = firstRelation == null ? null : "Object: " + firstRelation;
        return new ObjectInfo(firstTable, firstRelation, detail);
    }

    private static Map<String, String> predicates(List<Row> rows, Map<String, String> attributes) {
        Map<String, String> predicates = new LinkedHashMap<>();
        int index = 0;
        for (Row row : rows) {
            index++;
            String id = defaultText(row.text("PREDICATE_ID"), Integer.toString(index));
            String how = row.text("HOW_APPLIED");
            String when = row.text("WHEN_EVALUATED");
            String label = "Predicate[" + id + "]";
            if (how != null) label += " " + how;
            if (when != null) label += "@" + when;
            putUnique(predicates, label, row.text("PREDICATE_TEXT"));
            copyRow(attributes, "Predicate[" + index + "].", row, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));
        }
        return predicates;
    }

    private static void arguments(List<Row> rows, Map<String, String> attributes) {
        int index = 0;
        for (Row row : rows) {
            index++;
            String type = defaultText(row.text("ARGUMENT_TYPE"), Integer.toString(index));
            String value = firstNonBlank(row.text("LONG_ARGUMENT_VALUE"), row.text("ARGUMENT_VALUE"));
            putUnique(attributes, "Argument[" + type + "]", value);
            copyRow(attributes, "Argument[" + index + "].", row, Set.copyOf(Db2ExplainTables.STATEMENT_KEY));
        }
    }

    private static Row cardinalityStream(String operatorId, Row incomingEdge, List<Row> streams) {
        if (incomingEdge != null && "O".equalsIgnoreCase(incomingEdge.text("SOURCE_TYPE"))
                && operatorId.equals(incomingEdge.text("SOURCE_ID"))) {
            return incomingEdge;
        }
        for (Row stream : streams) {
            if ("O".equalsIgnoreCase(stream.text("SOURCE_TYPE"))
                    && operatorId.equals(stream.text("SOURCE_ID"))) {
                return stream;
            }
        }
        for (Row stream : streams) {
            if ("O".equalsIgnoreCase(stream.text("TARGET_TYPE"))
                    && operatorId.equals(stream.text("TARGET_ID"))) {
                return stream;
            }
        }
        return null;
    }

    static NormalizedNodeType mapNodeType(String operator) {
        String value = operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT);
        if (value.contains("HSJOIN")) return NormalizedNodeType.HASH_JOIN;
        if (value.contains("MSJOIN")) return NormalizedNodeType.MERGE_JOIN;
        if (value.contains("NLJOIN")) return NormalizedNodeType.NESTED_LOOP;
        if (value.contains("JOIN")) return NormalizedNodeType.JOIN;
        if (value.contains("IXSCAN") || value.contains("RIDSCN")) return NormalizedNodeType.INDEX_SCAN;
        if (value.contains("TBSCAN") || value.contains("FETCH")) return NormalizedNodeType.SCAN;
        if (value.contains("SORT")) return NormalizedNodeType.SORT;
        if (value.contains("GRPBY") || value.contains("AGG")) return NormalizedNodeType.AGGREGATE;
        if (value.contains("FILTER")) return NormalizedNodeType.FILTER;
        if (value.contains("ROWNUM") || value.contains("LIMIT")) return NormalizedNodeType.LIMIT;
        if (value.contains("UNION")) return NormalizedNodeType.UNION;
        if (value.contains("TEMP") || value.contains("SPOOL")) return NormalizedNodeType.MATERIALIZE;
        if (value.contains("TQ") || value.contains("PARALLEL")) return NormalizedNodeType.PARALLEL;
        if (value.contains("RETURN") || value.contains("GENROW") || value.contains("PROJECT")) {
            return NormalizedNodeType.PROJECTION;
        }
        return NormalizedNodeType.OTHER;
    }

    private static Map<String, TableData> tables(String raw) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DB2 Explain table envelope is not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("DB2 Explain table envelope is not an object");
        }
        JsonElement resultSetsElement = parsed.getAsJsonObject().get("resultSets");
        if (resultSetsElement == null || !resultSetsElement.isJsonArray()) {
            throw new IllegalArgumentException("DB2 Explain table envelope has no resultSets array");
        }
        Map<String, TableData> result = new LinkedHashMap<>();
        for (JsonElement element : resultSetsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("DB2 Explain result set is not an object");
            }
            JsonObject object = element.getAsJsonObject();
            String name = text(object.get("name"));
            JsonElement columnsElement = object.get("columns");
            JsonElement rowsElement = object.get("rows");
            if (name == null || columnsElement == null || !columnsElement.isJsonArray()
                    || rowsElement == null || !rowsElement.isJsonArray()) {
                throw new IllegalArgumentException("DB2 Explain result set metadata is invalid");
            }
            List<String> columns = new ArrayList<>();
            for (JsonElement columnElement : columnsElement.getAsJsonArray()) {
                if (!columnElement.isJsonObject()) {
                    throw new IllegalArgumentException("DB2 Explain column metadata is invalid");
                }
                String column = text(columnElement.getAsJsonObject().get("name"));
                if (column == null) {
                    throw new IllegalArgumentException("DB2 Explain column has no name");
                }
                columns.add(upper(column));
            }
            List<Row> rows = new ArrayList<>();
            for (JsonElement rowElement : rowsElement.getAsJsonArray()) {
                if (!rowElement.isJsonArray() || rowElement.getAsJsonArray().size() != columns.size()) {
                    throw new IllegalArgumentException("DB2 Explain row width does not match its columns");
                }
                Map<String, JsonElement> fields = new LinkedHashMap<>();
                for (int index = 0; index < columns.size(); index++) {
                    fields.put(columns.get(index), rowElement.getAsJsonArray().get(index));
                }
                rows.add(new Row(fields));
            }
            result.put(upper(name), new TableData(List.copyOf(rows)));
        }
        return result;
    }

    private static Map<String, List<Row>> group(TableData table, String column) {
        Map<String, List<Row>> result = new HashMap<>();
        if (table == null) {
            return result;
        }
        for (Row row : table.rows()) {
            String key = row.text(column);
            if (key != null) {
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    private static Map<String, List<Row>> objects(TableData table) {
        Map<String, List<Row>> result = new HashMap<>();
        if (table == null) {
            return result;
        }
        for (Row row : table.rows()) {
            result.computeIfAbsent(
                    objectKey(row.text("OBJECT_SCHEMA"), row.text("OBJECT_NAME")),
                    ignored -> new ArrayList<>()
            ).add(row);
        }
        return result;
    }

    private static void collectReachable(
            List<String> roots,
            Map<String, List<Row>> childrenByTarget,
            Set<String> reached
    ) {
        List<String> pending = new ArrayList<>(roots);
        while (!pending.isEmpty()) {
            String id = pending.remove(pending.size() - 1);
            if (!reached.add(id)) {
                continue;
            }
            for (Row edge : childrenByTarget.getOrDefault(id, List.of())) {
                String child = edge.text("SOURCE_ID");
                if (child != null) pending.add(child);
            }
        }
    }

    private static void copyRow(
            Map<String, String> target,
            String prefix,
            Row row,
            Set<String> excluded
    ) {
        row.fields().forEach((name, value) -> {
            if (!excluded.contains(name)) {
                putUnique(target, prefix + name, text(value));
            }
        });
    }

    private static void putUnique(Map<String, String> values, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String candidate = key;
        int suffix = 2;
        while (values.containsKey(candidate)) {
            candidate = key + "[" + suffix++ + "]";
        }
        values.put(candidate, value);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 ? value : null;
    }

    private static int compareNativeIds(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        } catch (RuntimeException ignored) {
            return left.compareTo(right);
        }
    }

    private static String objectKey(String schema, String name) {
        return defaultText(schema, "") + "\u0000" + defaultText(name, "");
    }

    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String result = value.getAsString();
        return result == null || result.isBlank() ? null : result.trim();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static void addLimitWarning(List<PlanDiagnostic> warnings, String message) {
        if (warnings.stream().noneMatch(warning -> PlanCodes.PLAN_LIMIT_REACHED.equals(warning.code()))) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, message));
        }
    }

    private record TableData(List<Row> rows) {
    }

    private record Row(Map<String, JsonElement> fields) {
        String text(String name) {
            return Db2PlanParser.text(fields.get(name));
        }

        BigDecimal decimal(String name) {
            JsonElement value = fields.get(name);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
            try {
                return value.getAsBigDecimal();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private record ObjectInfo(String table, String relation, String detail) {
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
