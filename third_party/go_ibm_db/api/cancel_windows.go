package api

import "syscall"

var procSQLCancel = mododbc32.NewProc("SQLCancel")

func SQLCancel(h SQLHSTMT) SQLRETURN {
	r, _, _ := syscall.Syscall(procSQLCancel.Addr(), 1, uintptr(h), 0, 0)
	return SQLRETURN(r)
}
