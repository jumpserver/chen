package org.jumpserver.chen.framework.datasource.plan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlanNode(
        String id,
        String nativeId,
        NormalizedNodeType nodeType,
        String nativeOperator,
        String logicalOperator,
        String physicalOperator,
        String detail,
        String table,
        String relation,
        BigDecimal rows,
        BigDecimal cost,
        BigDecimal startupCost,
        String rowsMeaning,
        String costMeaning,
        Map<String, String> predicates,
        Map<String, String> attributes,
        List<PlanNode> children
) {
    public PlanNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(nativeOperator, "nativeOperator");
        predicates = predicates == null ? Map.of() : Map.copyOf(predicates);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
