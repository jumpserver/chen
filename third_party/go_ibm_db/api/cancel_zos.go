//go:build zos

package api

import "github.com/ibmruntimes/go-recordio/v2/utils"

func SQLCancel(h SQLHSTMT) SQLRETURN {
	return SQLRETURN(utils.CfuncEbcdic(getFunc(&dll, "SQLCancel"), uintptr(h)))
}
