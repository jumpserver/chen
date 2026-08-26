package org.jumpserver.chen.framework.datasource.metadata;

/**
 * Canonical relation kind. The {@link #code()} value is the wire form used in
 * {@link ObjectRef}; keep it stable so it stays JSON-compatible with the
 * completion client contract ("table" / "view").
 */
public enum RelationKind {
    TABLE("table"),
    VIEW("view"),
    MATERIALIZED_VIEW("materialized_view");

    private final String code;

    RelationKind(String code) {
        this.code = code;
    }

    public String code() {
        return this.code;
    }

    public static RelationKind fromCode(String code) {
        for (var kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown relation kind: " + code);
    }
}
