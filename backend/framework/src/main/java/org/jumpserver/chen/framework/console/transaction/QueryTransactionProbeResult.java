package org.jumpserver.chen.framework.console.transaction;

import java.util.Objects;

public record QueryTransactionProbeResult(
        QueryTransactionState state,
        boolean probeFailed
) {
    public QueryTransactionProbeResult {
        Objects.requireNonNull(state, "state");
    }

    static QueryTransactionProbeResult observed(QueryTransactionState state) {
        return new QueryTransactionProbeResult(state, false);
    }

    static QueryTransactionProbeResult failed() {
        return new QueryTransactionProbeResult(QueryTransactionState.UNKNOWN, true);
    }
}
