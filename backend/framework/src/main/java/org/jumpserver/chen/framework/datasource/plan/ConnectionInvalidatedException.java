package org.jumpserver.chen.framework.datasource.plan;

import java.sql.SQLException;
import java.util.List;

public class ConnectionInvalidatedException extends SQLException {
    private final PlanDiagnostic originalError;
    private final PlanDiagnostic cleanupError;
    private final DialectPlanResult partialResult;

    public ConnectionInvalidatedException(
            String message,
            Throwable cause,
            PlanDiagnostic originalError,
            PlanDiagnostic cleanupError,
            DialectPlanResult partialResult
    ) {
        super(message, cause);
        this.originalError = originalError;
        this.cleanupError = cleanupError;
        this.partialResult = partialResult;
    }

    public PlanDiagnostic originalError() {
        return originalError;
    }

    public PlanDiagnostic cleanupError() {
        return cleanupError;
    }

    public DialectPlanResult partialResult() {
        return partialResult;
    }

    public List<PlanDiagnostic> diagnostics() {
        if (originalError == null && cleanupError == null) {
            return List.of();
        }
        if (originalError == null) {
            return List.of(cleanupError);
        }
        if (cleanupError == null) {
            return List.of(originalError);
        }
        return List.of(originalError, cleanupError);
    }
}
