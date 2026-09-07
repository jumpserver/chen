package org.jumpserver.chen.framework.console.transaction;

import java.util.Objects;

public record QueryTransactionProbeResult(
        QueryTransactionState state,
        boolean probeFailed,
        String detail
) {
    public QueryTransactionProbeResult {
        Objects.requireNonNull(state, "state");
    }

    public QueryTransactionProbeResult(QueryTransactionState state, boolean probeFailed) {
        this(state, probeFailed, null);
    }

    static QueryTransactionProbeResult observed(QueryTransactionState state) {
        return new QueryTransactionProbeResult(state, false, null);
    }

    static QueryTransactionProbeResult failed() {
        return failed(null);
    }

    static QueryTransactionProbeResult failed(String detail) {
        return new QueryTransactionProbeResult(QueryTransactionState.UNKNOWN, true, detail);
    }
}
