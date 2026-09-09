package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.plan.NormalizedNodeType;
import org.jumpserver.chen.framework.datasource.plan.PlanCodes;
import org.jumpserver.chen.framework.datasource.plan.PlanDiagnostic;
import org.jumpserver.chen.framework.datasource.plan.PlanNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class SqlServerPlanParser {
    public static final String RAW_FORMAT_VERSION = "sqlserver-showplan-xml";
    public static final String ROWS_MEANING = "SQL Server estimated output rows";
    public static final String COST_MEANING = "SQL Server estimated total subtree cost";

    private SqlServerPlanParser() {
    }

    public static ParseResult parse(String rawXml, int maxRawBytes, int maxNodes, int maxDepth) {
        return parse(List.of(rawXml == null ? "" : rawXml), maxRawBytes, maxNodes, maxDepth);
    }

    public static ParseResult parse(List<String> documents, int maxRawBytes, int maxNodes, int maxDepth) {
        List<PlanDiagnostic> warnings = new ArrayList<>();
        List<PlanNode> roots = new ArrayList<>();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger nodes = new AtomicInteger();
        boolean limited = false;
        long totalRawBytes = 0L;

        if (documents == null || documents.isEmpty()) {
            return ParseResult.failed(warnings, "SQL Server returned no Showplan XML documents");
        }
        for (String rawXml : documents) {
            if (rawXml == null || rawXml.isBlank()) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "SQL Server returned an empty Showplan XML document"));
                continue;
            }
            int documentBytes = rawXml.getBytes(StandardCharsets.UTF_8).length;
            totalRawBytes += documentBytes;
            if (documentBytes > maxRawBytes || totalRawBytes > maxRawBytes) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "SQL Server Showplan XML exceeded the raw size limit"));
                limited = true;
                continue;
            }

            Document document;
            try {
                document = parseDocument(rawXml);
            } catch (Exception e) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "SQL Server Showplan XML is not valid or safe to parse"));
                continue;
            }
            if (!"ShowPlanXML".equals(localName(document.getDocumentElement()))) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "XML document is not a SQL Server ShowPlanXML document"));
                continue;
            }

            List<Element> documentRoots = new ArrayList<>();
            findRootRelOps(document.getDocumentElement(), documentRoots);
            if (documentRoots.isEmpty()) {
                warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, "SQL Server Showplan XML contains no RelOp nodes"));
                continue;
            }
            for (Element root : documentRoots) {
                if (nodes.get() >= maxNodes) {
                    limited = true;
                    break;
                }
                roots.add(convert(root, ids, nodes, 1, maxNodes, maxDepth, warnings));
            }
        }

        if (limited && warnings.stream().noneMatch(warning -> PlanCodes.PLAN_LIMIT_REACHED.equals(warning.code()))) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, "SQL Server execution plan exceeded node or depth limits"));
        }
        if (roots.isEmpty()) {
            return new ParseResult(List.of(), List.copyOf(warnings), false);
        }
        return new ParseResult(List.copyOf(roots), List.copyOf(warnings), true);
    }

    static boolean isShowPlanXml(String rawXml, int maxBytes) {
        if (rawXml == null || rawXml.isBlank() || rawXml.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return false;
        }
        if (!rawXml.stripLeading().startsWith("<")) {
            return false;
        }
        try {
            return "ShowPlanXML".equals(localName(parseDocument(rawXml).getDocumentElement()));
        } catch (Exception e) {
            return false;
        }
    }

    private static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void findRootRelOps(Element element, List<Element> roots) {
        if ("RelOp".equals(localName(element))) {
            roots.add(element);
            return;
        }
        for (Element child : childElements(element)) {
            findRootRelOps(child, roots);
        }
    }

    private static PlanNode convert(
            Element relOp,
            AtomicInteger ids,
            AtomicInteger nodes,
            int depth,
            int maxNodes,
            int maxDepth,
            List<PlanDiagnostic> warnings
    ) {
        nodes.incrementAndGet();
        int displayId = ids.incrementAndGet();
        String nativeId = attribute(relOp, "NodeId");
        String logical = attribute(relOp, "LogicalOp");
        String physical = attribute(relOp, "PhysicalOp");
        String nativeOperator = firstNonBlank(physical, logical, "RelOp");
        Map<String, String> attributes = elementAttributes(relOp);
        Map<String, String> predicates = new LinkedHashMap<>();
        ObjectInfo object = collectOwnedDetails(relOp, attributes, predicates);

        List<PlanNode> children = new ArrayList<>();
        List<Element> childRelOps = new ArrayList<>();
        findNearestChildRelOps(relOp, relOp, childRelOps);
        if (depth >= maxDepth && !childRelOps.isEmpty()) {
            addLimitWarning(warnings, "SQL Server execution plan exceeded the depth limit");
        } else {
            for (Element child : childRelOps) {
                if (nodes.get() >= maxNodes) {
                    addLimitWarning(warnings, "SQL Server execution plan exceeded the node limit");
                    break;
                }
                children.add(convert(child, ids, nodes, depth + 1, maxNodes, maxDepth, warnings));
            }
        }

        BigDecimal rows = decimal(attribute(relOp, "EstimateRows"));
        BigDecimal cost = decimal(attribute(relOp, "EstimatedTotalSubtreeCost"));
        String detail = object.relation() == null ? null : "Object: " + object.relation();
        return new PlanNode(
                "sqlserver-" + displayId,
                nativeId,
                mapNodeType(logical, physical),
                nativeOperator,
                emptyToNull(logical),
                emptyToNull(physical),
                detail,
                object.table(),
                object.relation(),
                rows,
                cost,
                null,
                rows == null ? null : ROWS_MEANING,
                cost == null ? null : COST_MEANING,
                predicates,
                attributes,
                children
        );
    }

    private static ObjectInfo collectOwnedDetails(
            Element relOp,
            Map<String, String> attributes,
            Map<String, String> predicates
    ) {
        ObjectInfo object = new ObjectInfo(null, null);
        List<Element> stack = new ArrayList<>(childElements(relOp));
        int objectIndex = 0;
        int scalarIndex = 0;
        while (!stack.isEmpty()) {
            Element element = stack.remove(stack.size() - 1);
            if ("RelOp".equals(localName(element))) {
                continue;
            }
            String name = localName(element);
            if ("Object".equals(name)) {
                objectIndex++;
                NamedNodeMap objectAttributes = element.getAttributes();
                for (int index = 0; index < objectAttributes.getLength(); index++) {
                    Node attribute = objectAttributes.item(index);
                    putUnique(attributes, "Object[" + objectIndex + "]." + localName(attribute), attribute.getNodeValue());
                }
                if (object.table() == null) {
                    String table = emptyToNull(attribute(element, "Table"));
                    object = new ObjectInfo(table, qualifiedObjectName(element));
                }
            }
            if ("ScalarOperator".equals(name)) {
                String scalar = emptyToNull(attribute(element, "ScalarString"));
                if (scalar != null) {
                    scalarIndex++;
                    String predicateName = predicateAncestor(element, relOp);
                    if (predicateName == null) {
                        putUnique(attributes, "ScalarOperator[" + scalarIndex + "]", scalar);
                    } else {
                        putUnique(predicates, predicateName, scalar);
                    }
                }
            }
            stack.addAll(childElements(element));
        }
        return object;
    }

    private static String predicateAncestor(Element scalar, Element relOp) {
        Node current = scalar.getParentNode();
        while (current instanceof Element element && current != relOp) {
            String name = localName(element);
            if (name.toLowerCase(Locale.ROOT).contains("predicate") || "ProbeResidual".equals(name)) {
                return name;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private static void findNearestChildRelOps(Element root, Element current, List<Element> children) {
        for (Element child : childElements(current)) {
            if (child != root && "RelOp".equals(localName(child))) {
                children.add(child);
            } else {
                findNearestChildRelOps(root, child, children);
            }
        }
    }

    private static String qualifiedObjectName(Element object) {
        List<String> parts = new ArrayList<>();
        for (String name : List.of("Database", "Schema", "Table")) {
            String value = emptyToNull(attribute(object, name));
            if (value != null) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? null : String.join(".", parts);
    }

    private static Map<String, String> elementAttributes(Element element) {
        Map<String, String> values = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            values.put(localName(attribute), attribute.getNodeValue());
        }
        return values;
    }

    private static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    private static String attribute(Element element, String name) {
        if (element.hasAttribute(name)) {
            return element.getAttribute(name);
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (name.equals(localName(attribute))) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    private static String localName(Node node) {
        if (node == null) {
            return "";
        }
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name == null ? -1 : name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static NormalizedNodeType mapNodeType(String logical, String physical) {
        String value = (firstNonBlank(physical, "") + " " + firstNonBlank(logical, ""))
                .toLowerCase(Locale.ROOT);
        if (value.contains("nested loops")) return NormalizedNodeType.NESTED_LOOP;
        if (value.contains("hash match") && value.contains("join")) return NormalizedNodeType.HASH_JOIN;
        if (value.contains("merge join")) return NormalizedNodeType.MERGE_JOIN;
        if (value.contains("join")) return NormalizedNodeType.JOIN;
        if (value.contains("index scan") || value.contains("index seek")) return NormalizedNodeType.INDEX_SCAN;
        if (value.contains("table scan") || value.contains("remote scan")) return NormalizedNodeType.SCAN;
        if (value.contains("sort")) return NormalizedNodeType.SORT;
        if (value.contains("aggregate")) return NormalizedNodeType.AGGREGATE;
        if (value.contains("filter")) return NormalizedNodeType.FILTER;
        if (value.contains("top")) return NormalizedNodeType.LIMIT;
        if (value.contains("concatenation")) return NormalizedNodeType.UNION;
        if (value.contains("constant scan")) return NormalizedNodeType.VALUES;
        if (value.contains("spool")) return NormalizedNodeType.MATERIALIZE;
        if (value.contains("compute scalar") || value.contains("sequence project")) return NormalizedNodeType.PROJECTION;
        if (value.contains("parallelism")) return NormalizedNodeType.PARALLEL;
        if (value.contains("hash match")) return NormalizedNodeType.HASH;
        return NormalizedNodeType.OTHER;
    }

    private static void addLimitWarning(List<PlanDiagnostic> warnings, String message) {
        if (warnings.stream().noneMatch(warning -> PlanCodes.PLAN_LIMIT_REACHED.equals(warning.code()))) {
            warnings.add(PlanDiagnostic.of(PlanCodes.PLAN_LIMIT_REACHED, message));
        }
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record ObjectInfo(String table, String relation) {
    }

    public record ParseResult(List<PlanNode> roots, List<PlanDiagnostic> warnings, boolean structured) {
        private static ParseResult failed(List<PlanDiagnostic> warnings, String message) {
            List<PlanDiagnostic> all = new ArrayList<>(warnings);
            all.add(PlanDiagnostic.of(PlanCodes.PLAN_PARSE_FAILED, message));
            return new ParseResult(List.of(), List.copyOf(all), false);
        }
    }
}
