//go:build ibmdb

package engine

import (
	"errors"
	ibmdb "github.com/ibmdb/go_ibm_db"
)

func db2ServerCancellation(err error) bool {
	var native *ibmdb.Error
	return errors.As(err, &native) && native.ChenCancellationAcknowledged()
}

// CLI transport failures/timeouts cannot prove a dispatched write was rejected.
func db2UncertainNativeError(err error) bool {
	var native *ibmdb.Error
	return errors.As(err, &native) && native.ChenOutcomeUnknown()
}
