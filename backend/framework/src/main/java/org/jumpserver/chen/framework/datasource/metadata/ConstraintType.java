package org.jumpserver.chen.framework.datasource.metadata;

import java.util.Locale;

public enum ConstraintType {
    PRIMARY_KEY("PRIMARY KEY"),
    FOREIGN_KEY("FOREIGN KEY"),
    UNIQUE("UNIQUE"),
    CHECK("CHECK");

    private final String code;

    ConstraintType(String code) {
        this.code = code;
    }

    public String code() {
        return this.code;
    }

    public static ConstraintType fromDatabaseValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Constraint type is required");
        }
        var normalized = value.trim().replace('_', ' ').toUpperCase(Locale.ROOT);
        for (var type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown constraint type: " + value);
    }
}
