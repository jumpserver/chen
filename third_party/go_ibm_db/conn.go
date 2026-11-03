// Copyright 2024 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package go_ibm_db

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"github.com/ibmdb/go_ibm_db/api"
	trc "github.com/ibmdb/go_ibm_db/log2"
	"runtime"
	"sync/atomic"
	"unsafe"
)

type Conn struct {
	h               api.SQLHDBC
	tx              *Tx
	cancellationAck atomic.Uint64
}

func (d *Driver) Open(dsn string) (driver.Conn, error) {
	trc.Trace1("conn.go: Open() - ENTRY")

	var out api.SQLHANDLE
	ret := api.SQLAllocHandle(api.SQL_HANDLE_DBC, api.SQLHANDLE(d.h), &out)
	if IsError(ret) {
		return nil, NewError("SQLAllocHandle", d.h)
	}
	h := api.SQLHDBC(out)
	drv.Stats.updateHandleCount(api.SQL_HANDLE_DBC, 1)

	b := api.StringToUTF16(dsn)
	if runtime.GOOS == "zos" {
		ret = api.SQLDriverConnect(h, 0,
			(*api.SQLWCHAR)(unsafe.Pointer(&b[0])), api.SQLSMALLINT(2*len(b)), // odbc api on zos doesn't handle null terminated strings, the exact size is passed
			nil, 0, nil, api.SQL_DRIVER_NOPROMPT)
	} else {
		ret = api.SQLDriverConnect(h, 0,
			(*api.SQLWCHAR)(unsafe.Pointer(&b[0])), api.SQLSMALLINT(len(b)),
			nil, 0, nil, api.SQL_DRIVER_NOPROMPT)
	}
	if IsError(ret) {
		defer releaseHandle(h)
		return nil, NewError("SQLDriverConnect", h)
	}
	trc.Trace1("conn.go: Open() - EXIT")
	return &Conn{h: h}, nil
}

func (c *Conn) Close() error {
	trc.Trace1("conn.go: Close() - ENTRY")

	if c.h == api.SQLHDBC(api.SQL_NULL_HDBC) {
		return nil
	}
	var rollbackErr error
	if c.tx != nil {
		// Discard must not leave a live user transaction or enable auto-commit
		// (which could commit it). A later stale Tx must not touch this handle.
		c.tx = nil
		ret := api.SQLEndTran(api.SQL_HANDLE_DBC, api.SQLHANDLE(c.h), api.SQL_ROLLBACK)
		if IsError(ret) {
			rollbackErr = NewError("SQLEndTran", c.h)
		}
	}
	ret := api.SQLDisconnect(c.h)
	if IsError(ret) {
		return errors.Join(rollbackErr, NewError("SQLDisconnect", c.h))
	}

	h := c.h
	c.h = api.SQLHDBC(api.SQL_NULL_HDBC)
	trc.Trace1("conn.go: Close() - EXIT")
	return errors.Join(rollbackErr, releaseHandle(h))
}

// Query method executes the statement with out prepare if no args provided, and a driver.ErrSkip otherwise (handled by sql.go to execute usual preparedStmt)
func (c *Conn) Query(query string, args []driver.Value) (driver.Rows, error) {
	values := make([]driver.NamedValue, len(args))
	for i, v := range args {
		values[i] = driver.NamedValue{Ordinal: i + 1, Value: v}
	}
	return c.QueryContext(context.Background(), query, values)
}
func (c *Conn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	trc.Trace1("conn.go: Query() - ENTRY")
	trc.Trace1(fmt.Sprintf("query = %s", query))

	if len(args) > 0 {
		// Not implemented for queries with parameters
		return nil, driver.ErrSkip
	}
	var out api.SQLHANDLE
	var os *ODBCStmt
	ret := api.SQLAllocHandle(api.SQL_HANDLE_STMT, api.SQLHANDLE(c.h), &out)
	if IsError(ret) {
		return nil, NewError("SQLAllocHandle", c.h)
	}
	h := api.SQLHSTMT(out)
	drv.Stats.updateHandleCount(api.SQL_HANDLE_STMT, 1)
	b := api.StringToUTF16(query)
	ret, cancelErr := callCLIContext(ctx, h, func() api.SQLRETURN { return api.SQLExecDirect(h, (*api.SQLWCHAR)(unsafe.Pointer(&b[0])), api.SQL_NTS) })
	if cancelErr != nil {
		_ = releaseHandle(h)
		return nil, cancelErr
	}
	if IsError(ret) {
		defer releaseHandle(h)
		return nil, c.noteCancellation(NewError("SQLExecDirectW", h))
	}
	ps, err := ExtractParameters(h)
	if err != nil {
		defer releaseHandle(h)
		return nil, err
	}
	os = &ODBCStmt{
		h:          h,
		owner:      c,
		Parameters: ps,
		usedByRows: true}
	err = os.BindColumns()
	if err != nil {
		_ = releaseHandle(h)
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		_ = releaseHandle(h)
		return nil, err
	}
	trc.Trace1("conn.go: Query() - EXIT")
	return &Rows{os: os, ctx: ctx}, nil
}
