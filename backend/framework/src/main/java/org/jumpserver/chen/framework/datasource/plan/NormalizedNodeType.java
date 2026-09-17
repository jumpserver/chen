package org.jumpserver.chen.framework.datasource.plan;

public enum NormalizedNodeType {
    SCAN,
    INDEX_SCAN,
    INDEX_ONLY_SCAN,
    JOIN,
    HASH_JOIN,
    MERGE_JOIN,
    NESTED_LOOP,
    SORT,
    AGGREGATE,
    FILTER,
    LIMIT,
    SUBQUERY,
    HASH,
    PARALLEL,
    UNION,
    VALUES,
    MATERIALIZE,
    PROJECTION,
    OTHER
}
