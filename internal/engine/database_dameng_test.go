package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	dm "gitee.com/chunanyong/dm"
)

type damengTestConn struct {
	calls, busy, closes, unsafe atomic.Int32
	cancelable                  atomic.Bool
	during                      func()
	rowDuring                   func()
}

func (c *damengTestConn) operation(ctx context.Context) {
	c.calls.Add(1)
	c.busy.Add(1)
	defer c.busy.Add(-1)
	if _, deadline := ctx.Deadline(); deadline || ctx.Done() != nil || ctx.Err() != nil {
		c.cancelable.Store(true)
	}
	if c.during != nil {
		c.during()
	}
}
func (c *damengTestConn) Close() error {
	if c.busy.Load() != 0 {
		c.unsafe.Add(1)
	}
	c.closes.Add(1)
	return nil
}
func (c *damengTestConn) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}
func (c *damengTestConn) BeginTx(ctx context.Context, _ driver.TxOptions) (driver.Tx, error) {
	c.operation(ctx)
	return damengTestTx{}, nil
}
func (c *damengTestConn) Ping(ctx context.Context) error           { c.operation(ctx); return nil }
func (c *damengTestConn) ResetSession(ctx context.Context) error   { c.operation(ctx); return nil }
func (c *damengTestConn) CheckNamedValue(*driver.NamedValue) error { return nil }
func (c *damengTestConn) ExecContext(ctx context.Context, _ string, _ []driver.NamedValue) (driver.Result, error) {
	c.operation(ctx)
	return driver.RowsAffected(1), nil
}
func (c *damengTestConn) QueryContext(ctx context.Context, _ string, _ []driver.NamedValue) (driver.Rows, error) {
	c.operation(ctx)
	return &damengTestRows{c: c}, nil
}
func (c *damengTestConn) Prepare(q string) (driver.Stmt, error) {
	return c.PrepareContext(context.Background(), q)
}
func (c *damengTestConn) PrepareContext(ctx context.Context, _ string) (driver.Stmt, error) {
	c.operation(ctx)
	return &damengTestStmt{c}, nil
}

type damengTestTx struct{}

func (damengTestTx) Commit() error   { return nil }
func (damengTestTx) Rollback() error { return nil }

type damengTestStmt struct{ c *damengTestConn }

func (*damengTestStmt) Close() error                             { return nil }
func (*damengTestStmt) NumInput() int                            { return -1 }
func (*damengTestStmt) CheckNamedValue(*driver.NamedValue) error { return nil }
func (s *damengTestStmt) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), nil)
}
func (s *damengTestStmt) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), nil)
}
func (s *damengTestStmt) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	return s.c.ExecContext(ctx, "", args)
}
func (s *damengTestStmt) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	return s.c.QueryContext(ctx, "", args)
}

type damengTestRows struct{ c *damengTestConn }

func (*damengTestRows) Close() error      { return nil }
func (*damengTestRows) Columns() []string { return []string{"VALUE"} }
func (r *damengTestRows) Next(values []driver.Value) error {
	r.c.busy.Add(1)
	defer r.c.busy.Add(-1)
	if r.c.rowDuring != nil {
		r.c.rowDuring()
	}
	values[0] = int64(42)
	return nil
}
func (*damengTestRows) HasNextResultSet() bool                            { return false }
func (*damengTestRows) NextResultSet() error                              { return io.EOF }
func (*damengTestRows) ColumnTypeDatabaseTypeName(int) string             { return "BIGINT" }
func (*damengTestRows) ColumnTypeScanType(int) reflect.Type               { return reflect.TypeFor[int64]() }
func (*damengTestRows) ColumnTypeLength(int) (int64, bool)                { return 8, true }
func (*damengTestRows) ColumnTypeNullable(int) (bool, bool)               { return false, true }
func (*damengTestRows) ColumnTypePrecisionScale(int) (int64, int64, bool) { return 19, 0, true }

type damengTestConnector struct{ c *damengTestConn }

func (c damengTestConnector) Connect(ctx context.Context) (driver.Conn, error) {
	c.c.operation(ctx)
	return c.c, nil
}
func (c damengTestConnector) Driver() driver.Driver { return damengTestDriver{} }

type damengTestDriver struct{}

func (damengTestDriver) Open(string) (driver.Conn, error) { panic("connector required") }

func TestDamengAdapterNeverStartsCancelableWatcher(t *testing.T) {
	operations := map[string]func(*damengConn, context.Context) error{
		"begin": func(c *damengConn, ctx context.Context) error {
			_, err := c.BeginTx(ctx, driver.TxOptions{})
			return err
		},
		"ping":  (*damengConn).Ping,
		"reset": (*damengConn).ResetSession,
		"exec": func(c *damengConn, ctx context.Context) error {
			_, err := c.ExecContext(ctx, "UPDATE owned SET value=1", nil)
			return err
		},
		"query": func(c *damengConn, ctx context.Context) error {
			_, err := c.QueryContext(ctx, "SELECT 1", nil)
			return err
		},
		"prepare": func(c *damengConn, ctx context.Context) error {
			_, err := c.PrepareContext(ctx, "SELECT ?")
			return err
		},
		"prepared-exec": func(c *damengConn, ctx context.Context) error {
			s := &damengStmt{&damengTestStmt{c.damengContextConn.(*damengTestConn)}, c}
			_, err := s.ExecContext(ctx, nil)
			return err
		},
		"prepared-query": func(c *damengConn, ctx context.Context) error {
			s := &damengStmt{&damengTestStmt{c.damengContextConn.(*damengTestConn)}, c}
			_, err := s.QueryContext(ctx, nil)
			return err
		},
	}
	for name, operation := range operations {
		t.Run(name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			raw := &damengTestConn{during: cancel}
			conn := &damengConn{damengContextConn: raw}
			err := operation(conn, ctx)
			if !errors.Is(err, context.Canceled) || errors.Is(err, driver.ErrBadConn) {
				t.Fatalf("interrupted dispatched operation must return context error without replay: %v", err)
			}
			if raw.cancelable.Load() || raw.unsafe.Load() != 0 || raw.closes.Load() != 1 || conn.IsValid() {
				t.Fatalf("unsafe cancellation: cancelable=%v overlap=%d closes=%d valid=%v", raw.cancelable.Load(), raw.unsafe.Load(), raw.closes.Load(), conn.IsValid())
			}
			if err := operation(conn, ctx); !errors.Is(err, context.Canceled) || raw.calls.Load() != 1 {
				t.Fatal("already canceled request reached driver")
			}
		})
	}
	t.Run("connect", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		raw := &damengTestConn{during: cancel}
		_, err := (damengConnector{Connector: damengTestConnector{raw}}).Connect(ctx)
		if !errors.Is(err, context.Canceled) || raw.cancelable.Load() || raw.closes.Load() != 1 || raw.unsafe.Load() != 0 {
			t.Fatalf("connect cancellation was not drained: %v", err)
		}
	})
}

func TestDamengRowsKeepMetadataAndDrainBeforeCanceledClose(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	raw := &damengTestConn{rowDuring: func() { cancel(); time.Sleep(10 * time.Millisecond) }}
	db := sql.OpenDB(damengConnector{Connector: damengTestConnector{raw}})
	defer db.Close()
	rows, err := db.QueryContext(ctx, "SELECT value")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	columns, err := rows.ColumnTypes()
	if err != nil || len(columns) != 1 || columns[0].DatabaseTypeName() != "BIGINT" || columns[0].ScanType() != reflect.TypeFor[int64]() {
		t.Fatalf("lost driver column metadata: %v %v", columns, err)
	}
	if p, s, ok := columns[0].DecimalSize(); !ok || p != 19 || s != 0 {
		t.Fatal("lost precision metadata")
	}
	if rows.Next() || !errors.Is(rows.Err(), context.Canceled) {
		t.Fatal("canceled fetch returned success")
	}
	_ = rows.Close()
	if raw.cancelable.Load() || raw.unsafe.Load() != 0 || raw.closes.Load() == 0 {
		t.Fatal("row cleanup closed vendor connection concurrently or failed to discard it")
	}
}

func TestDamengSocketBoundsEachReadAndWrite(t *testing.T) {
	for _, write := range []bool{false, true} {
		name := "read"
		if write {
			name = "write"
		}
		t.Run(name, func(t *testing.T) {
			client, server := net.Pipe()
			defer server.Close()
			conn := &damengSocket{client, 30 * time.Millisecond, 30 * time.Millisecond}
			defer conn.Close()
			started := time.Now()
			var err error
			if write {
				_, err = conn.Write([]byte{1})
			} else {
				_, err = conn.Read(make([]byte, 1))
			}
			var timeout net.Error
			if !errors.As(err, &timeout) || !timeout.Timeout() || time.Since(started) > time.Second {
				t.Fatalf("socket operation was not bounded: %v", err)
			}
			if _, err := server.Read(make([]byte, 1)); !errors.Is(err, io.EOF) {
				t.Fatalf("timed out transport stayed open: %v", err)
			}
		})
	}
}

func TestDamengTransportErrorsCannotBecomeKnownSQLFailures(t *testing.T) {
	for _, code := range []int32{6001, 6060, 20000, 20001} {
		vendorErr := &dm.DmError{ErrCode: code, ErrText: "private endpoint"}
		if isServerError(vendorErr) || !isTransportError(vendorErr) {
			t.Fatalf("transport code %d was misclassified", code)
		}
		raw := &damengTestConn{}
		conn := &damengConn{damengContextConn: raw}
		err := conn.finish(context.Background(), vendorErr)
		if !errors.Is(err, errDamengTransport) || errors.Is(err, driver.ErrBadConn) || conn.IsValid() || raw.closes.Load() != 1 {
			t.Fatalf("failed transport could be reused or replayed: %v", err)
		}
	}
	if !isServerError(&dm.DmError{ErrCode: -6602, ErrText: "syntax error"}) {
		t.Fatal("actual SQL errors must preserve their known failure status")
	}
}
