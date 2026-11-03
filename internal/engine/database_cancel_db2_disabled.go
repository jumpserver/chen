//go:build !ibmdb

package engine

func db2ServerCancellation(error) bool   { return false }
func db2UncertainNativeError(error) bool { return false }
