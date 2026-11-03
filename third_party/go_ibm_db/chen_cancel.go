package go_ibm_db

import (
	"context"
	"errors"
	"github.com/ibmdb/go_ibm_db/api"
)

func callCLIContext(ctx context.Context, h api.SQLHSTMT, call func() api.SQLRETURN) (api.SQLRETURN, error) {
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	stop := watchNativeCancellation(ctx, func() { _ = api.SQLCancel(h) })
	defer stop()
	ret := call()
	stop()
	// Only the target call's native diagnostics (HY008) confirm cancellation.
	// Preserve native errors. A success racing cancellation has unknown outcome.
	if ret >= 0 && ctx.Err() != nil {
		return ret, ctx.Err()
	}
	return ret, nil
}

// ChenCancellationSequence counts target-function HY008/57014 acknowledgements,
// never SQLCancel's return code. It survives database/sql's ctx.Err replacement.
func (c *Conn) ChenCancellationSequence() uint64 { return c.cancellationAck.Load() }
func (c *Conn) noteCancellation(err error) error {
	if c == nil {
		return err
	}
	var native *Error
	if errors.As(err, &native) && native.ChenCancellationAcknowledged() {
		c.cancellationAck.Add(1)
	}
	return err
}

// ChenOutcomeUnknown distinguishes CLI transport and client timeout diagnostics
// from a known statement rejection. Do not automatically repeat these outcomes.
func (e *Error) ChenOutcomeUnknown() bool {
	for _, d := range e.Diag {
		s := d.State
		if len(s) >= 2 && s[:2] == "08" || s == "40003" || s == "58004" || s == "58006" || s == "HYT00" || s == "HYT01" {
			return true
		}
	}
	return false
}
func (e *Error) ChenCancellationAcknowledged() bool {
	switch e.APIName {
	case "SQLPrepare", "SQLExecDirectW", "SQLExecDirect", "SQLExecute", "SQLFetch", "SQLMoreResults", "SQLGetData":
	default:
		return false
	}
	if e.ChenOutcomeUnknown() {
		return false
	}
	for _, d := range e.Diag {
		if d.State == "HY008" || d.State == "57014" {
			return true
		}
	}
	return false
}
