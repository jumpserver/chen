package org.jumpserver.chen.modules.dameng;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the numbered, indented text emitted by DM's ordinary EXPLAIN. */
public final class DmPlanParser {
    public static final String RAW_FORMAT_VERSION = "dm-explain-text";
    public static final String ROWS_MEANING = "DM estimated rows";
    public static final String COST_MEANING = "DM optimizer cost";

    private static final Pattern NODE = Pattern.compile(
            "^(\\s*)(\\d+)[ \\t](\\s*)#?([^:\\[]+?)\\s*:?\\s*\\[\\s*([^,\\]]+)\\s*,\\s*([^,\\]]+)\\s*,\\s*([^\\]]+)\\s*\\](.*)$"
    );

    private DmPlanParser() {
    }

    public static ParseResult parse(String rawText, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return ParseResult.failed(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Empty DM EXPLAIN text"));
        }

        List<MutableNode> roots = new ArrayList<>();
        List<MutableNode> stack = new ArrayList<>();
        MutableNode current = null;
        int count = 0;
        int baseIndent = -1;
        for (String line : rawText.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            Matcher matcher = NODE.matcher(line);
            if (matcher.matches()) {
                int indent = visualIndent(matcher.group(1)) + visualIndent(matcher.group(3));
                if (baseIndent < 0) {
                    baseIndent = indent;
                }
                int level = Math.max(0, (indent - baseIndent) / 2);
                if (++count > maxNodes || level + 1 > maxDepth) {
                    warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "DM execution plan exceeded size limits"));
                    break;
                }
                String ordinal = matcher.group(2);
                String operator = matcher.group(4).trim();
                BigDecimal cost = decimal(matcher.group(5));
                BigDecimal rows = decimal(matcher.group(6));
                String bytes = matcher.group(7).trim();
                String tail = matcher.group(8).trim();
                MutableNode node = new MutableNode(
                        "n" + count, ordinal, operator, rows, cost, bytes,
                        tail == null || tail.isBlank() ? null : tail
                );
                while (stack.size() > level) {
                    stack.remove(stack.size() - 1);
                }
                if (level > 0 && !stack.isEmpty()) {
                    stack.get(stack.size() - 1).children.add(node);
                } else {
                    roots.add(node);
                }
                stack.add(node);
                current = node;
                continue;
            }
            if (current != null) {
                current.appendDetail(line.strip());
            } else {
                warnings.add(PlanDiagnostic.of(
                        PlanCodes.PLAN_PARSE_FAILED,
                        "DM EXPLAIN returned an unrecognized leading line; it remains available in raw text"
                ));
            }
        }

        if (roots.isEmpty()) {
            return new ParseResult(List.of(), warnings, false,
                    PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "Unrecognized DM EXPLAIN text shape"));
        }
        return new ParseResult(roots.stream().map(MutableNode::freeze).toList(), warnings, true, null);
    }

    private static int visualIndent(String value) {
        int result = 0;
        for (int i = 0; value != null && i < value.length(); i++) {
            result += value.charAt(i) == '\t' ? 4 : 1;
        }
        return result;
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static NormalizedNodeType mapNodeType(String operator) {
        String value = operator == null ? "" : operator.toUpperCase(Locale.ROOT);
        if (value.contains("JOIN") || value.contains("NL") || value.contains("HASH2")) {
            return value.contains("HASH") ? NormalizedNodeType.HASH_JOIN : NormalizedNodeType.JOIN;
        }
        if (value.contains("INDEX") || value.contains("SSEK") || value.contains("BLKUP")) {
            return NormalizedNodeType.INDEX_SCAN;
        }
        if (value.contains("SCAN") || value.contains("CSCN") || value.contains("SSCN")) {
            return NormalizedNodeType.SCAN;
        }
        if (value.contains("SORT")) {
            return NormalizedNodeType.SORT;
        }
        if (value.contains("AGG") || value.contains("GROUP")) {
            return NormalizedNodeType.AGGREGATE;
        }
        if (value.contains("FILTER") || value.contains("SLCT")) {
            return NormalizedNodeType.FILTER;
        }
        if (value.contains("LIMIT") || value.contains("TOP")) {
            return NormalizedNodeType.LIMIT;
        }
        if (value.contains("UNION")) {
            return NormalizedNodeType.UNION;
        }
        if (value.contains("PRJT") || value.contains("PROJECT")) {
            return NormalizedNodeType.PROJECTION;
        }
        return NormalizedNodeType.OTHER;
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

    private static final class MutableNode {
        private final String id;
        private final String nativeId;
        private final String operator;
        private final BigDecimal rows;
        private final BigDecimal cost;
        private final String bytes;
        private final List<MutableNode> children = new ArrayList<>();
        private String detail;

        private MutableNode(
                String id, String nativeId, String operator, BigDecimal rows,
                BigDecimal cost, String bytes, String detail
        ) {
            this.id = id;
            this.nativeId = nativeId;
            this.operator = operator;
            this.rows = rows;
            this.cost = cost;
            this.bytes = bytes;
            this.detail = detail;
        }

        private void appendDetail(String continuation) {
            detail = detail == null ? continuation : detail + "\n" + continuation;
        }

        private PlanNode freeze() {
            Map<String, String> attributes = new LinkedHashMap<>();
            if (bytes != null && !bytes.isBlank()) {
                attributes.put("bytes", bytes);
            }
            return new PlanNode(
                    id, nativeId, mapNodeType(operator), operator, null, operator,
                    detail, null, null, rows, cost, null,
                    rows == null ? null : ROWS_MEANING,
                    cost == null ? null : COST_MEANING,
                    Map.of(), attributes, children.stream().map(MutableNode::freeze).toList()
            );
        }
    }
}
