package org.jumpserver.chen.framework.datasource.plan;

import java.util.Objects;

public record PlanEffects(
        SessionState sessionState,
        TransactionState transactionState,
        AuxiliaryStorage auxiliaryStorage,
        ConnectionDisposition connectionDisposition
) {
    public PlanEffects {
        Objects.requireNonNull(sessionState, "sessionState");
        Objects.requireNonNull(transactionState, "transactionState");
        Objects.requireNonNull(auxiliaryStorage, "auxiliaryStorage");
        Objects.requireNonNull(connectionDisposition, "connectionDisposition");
    }

    public enum SessionState {
        UNCHANGED,
        RESTORED,
        UNKNOWN
    }

    public enum TransactionState {
        UNCHANGED,
        PARTICIPATED,
        FAILED,
        UNKNOWN
    }

    public enum AuxiliaryStorage {
        NONE,
        CLEANED,
        RESIDUAL,
        UNKNOWN
    }

    public enum ConnectionDisposition {
        REUSE,
        DISCARD
    }

    public static PlanEffects unchangedReuse() {
        return new PlanEffects(
                SessionState.UNCHANGED,
                TransactionState.UNCHANGED,
                AuxiliaryStorage.NONE,
                ConnectionDisposition.REUSE
        );
    }

    public static PlanEffects savepointParticipated() {
        return new PlanEffects(
                SessionState.UNCHANGED,
                TransactionState.PARTICIPATED,
                AuxiliaryStorage.NONE,
                ConnectionDisposition.REUSE
        );
    }

    public static PlanEffects discard(SessionState sessionState, TransactionState transactionState) {
        return new PlanEffects(
                sessionState,
                transactionState,
                AuxiliaryStorage.NONE,
                ConnectionDisposition.DISCARD
        );
    }
}
