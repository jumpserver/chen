package org.jumpserver.chen.framework.datasource.plan;

import java.util.Objects;

public record PlanDiagnostic(
        String code,
        String message,
        String sqlState,
        String vendorCode
) {
    public PlanDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static PlanDiagnostic of(String code, String message) {
        return new PlanDiagnostic(code, message, null, null);
    }
}
