package org.jumpserver.chen.framework.datasource.plan;

import java.util.Objects;

public record PlanPrerequisite(
        String code,
        Status status,
        String message,
        String remediation
) {
    public PlanPrerequisite {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    public enum Status {
        MET,
        UNMET,
        UNKNOWN
    }

    public static PlanPrerequisite met(String code, String message) {
        return new PlanPrerequisite(code, Status.MET, message, null);
    }

    public static PlanPrerequisite unmet(String code, String message, String remediation) {
        return new PlanPrerequisite(code, Status.UNMET, message, remediation);
    }

    public static PlanPrerequisite unknown(String code, String message, String remediation) {
        return new PlanPrerequisite(code, Status.UNKNOWN, message, remediation);
    }
}
