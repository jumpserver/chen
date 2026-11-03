//go:build (aix || darwin || linux) && cgo

package api

// #include <sqlcli1.h>
import "C"

// SQLCancel acknowledges an interrupt request, not the target call's outcome.
func SQLCancel(h SQLHSTMT) SQLRETURN { return SQLRETURN(C.SQLCancel(C.SQLHSTMT(h))) }
