package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/standalone"
)

// This transparent test-only adapter injects loss of the acknowledgement after
// PostgreSQL has actually committed/rolled back. Production code receives an
// ordinary driver I/O error, not a testing hook or a fabricated database result.
type editFaultConnector struct {
	conn  *sql.Conn
	fault string
}

func (c *editFaultConnector) Connect(context.Context) (driver.Conn, error) {
	return &editFaultConn{conn: c.conn, fault: c.fault}, nil
}
func (c *editFaultConnector) Driver() driver.Driver { return c }
func (c *editFaultConnector) Open(string) (driver.Conn, error) {
	return c.Connect(context.Background())
}

type editFaultConn struct {
	conn  *sql.Conn
	tx    *sql.Tx
	fault string
}

func (c *editFaultConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("test adapter requires context operations")
}
func (c *editFaultConn) Close() error                   { return c.conn.Close() }
func (c *editFaultConn) Ping(ctx context.Context) error { return c.conn.PingContext(ctx) }
func (c *editFaultConn) ChenTransactionState() string {
	state, _ := editTransactionState(context.Background(), &console{conn: c.conn}, "postgresql")
	return string(state)
}
func (c *editFaultConn) Begin() (driver.Tx, error) {
	tx, err := c.conn.BeginTx(context.Background(), nil)
	if err != nil {
		return nil, err
	}
	c.tx = tx
	return &editFaultTx{c}, nil
}
func editFaultArgs(args []driver.NamedValue) []any {
	out := make([]any, len(args))
	for i, a := range args {
		out[i] = a.Value
	}
	return out
}
func (c *editFaultConn) ExecContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Result, error) {
	if c.tx != nil {
		return c.tx.ExecContext(ctx, q, editFaultArgs(args)...)
	}
	return c.conn.ExecContext(ctx, q, editFaultArgs(args)...)
}
func (c *editFaultConn) QueryContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Rows, error) {
	var rows *sql.Rows
	var err error
	if c.tx != nil {
		rows, err = c.tx.QueryContext(ctx, q, editFaultArgs(args)...)
	} else {
		rows, err = c.conn.QueryContext(ctx, q, editFaultArgs(args)...)
	}
	if err != nil {
		return nil, err
	}
	columns, _ := rows.Columns()
	types, _ := rows.ColumnTypes()
	return &editFaultRows{rows: rows, columns: columns, types: types}, nil
}

type editFaultRows struct {
	rows    *sql.Rows
	columns []string
	types   []*sql.ColumnType
}

func (r *editFaultRows) Columns() []string { return r.columns }
func (r *editFaultRows) ColumnTypeDatabaseTypeName(i int) string {
	return r.types[i].DatabaseTypeName()
}
func (r *editFaultRows) Close() error { return r.rows.Close() }
func (r *editFaultRows) Next(dst []driver.Value) error {
	if !r.rows.Next() {
		if err := r.rows.Err(); err != nil {
			return err
		}
		return io.EOF
	}
	values := make([]any, len(dst))
	targets := make([]any, len(dst))
	for i := range targets {
		targets[i] = &values[i]
	}
	if err := r.rows.Scan(targets...); err != nil {
		return err
	}
	for i, value := range values {
		dst[i] = value
	}
	return nil
}

type editFaultTx struct{ c *editFaultConn }

func (tx *editFaultTx) Commit() error {
	err := tx.c.tx.Commit()
	tx.c.tx = nil
	if err == nil && tx.c.fault == "commit" {
		tx.c.fault = ""
		return io.ErrUnexpectedEOF
	}
	return err
}
func (tx *editFaultTx) Rollback() error {
	err := tx.c.tx.Rollback()
	tx.c.tx = nil
	if err == nil && tx.c.fault == "rollback" {
		tx.c.fault = ""
		return io.ErrUnexpectedEOF
	}
	return err
}

func TestGridLostAcknowledgementLive(t *testing.T) {
	path := os.Getenv("CHEN_WRITE_BACKENDS")
	if path == "" {
		t.Skip("explicit disposable-table write fixture required")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private fixture")
	}
	for _, b := range cfg.Backends {
		if b.Selector != "local-pg" {
			continue
		}
		for _, fault := range []string{"commit", "rollback"} {
			t.Run(fault, func(t *testing.T) {
				provider := singleIntegrationProvider(t, path, filepath.Join(t.TempDir(), "audit"))
				server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 30 * time.Second})
				hs := httptest.NewServer(server.Handler())
				defer hs.Close()
				defer server.Close()
				h := &integrationHTTP{t: t, url: hs.URL, client: &http.Client{Timeout: 35 * time.Second}}
				owner := singleIntegrationSession(h, b.Selector, "grid-fault-"+integrationKey())
				cv := h.console(owner, b.Database)
				name := "CHEN_F_" + integrationKey()[:12]
				table := editQualified(cv.Schema, name, "postgresql")
				h.run(owner, cv, "CREATE TABLE "+table+" (id BIGINT PRIMARY KEY, title TEXT)")
				observer, err := openDatabase(readonlyDriverConfig(b), 1)
				if err != nil {
					t.Fatal("observer unavailable")
				}
				defer observer.Close()
				defer func() {
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
					defer cancel()
					if _, err := observer.ExecContext(ctx, "DROP TABLE "+table); err != nil {
						t.Error("owned fault table cleanup failed")
					}
				}()
				h.run(owner, cv, "INSERT INTO "+table+" VALUES (1,'original')")
				base := "/consoles/" + cv.ID
				var browse ExecutionView
				h.request("POST", base+"/browse", owner.Token, map[string]any{"table": name, "request_id": integrationKey()}, 202, &browse)
				browse = h.wait(owner, browse.ID, "succeeded")
				rid := browse.ResultIDs[0]
				var meta editMetadataView
				h.request("GET", base+"/results/"+rid+"/edit", owner.Token, nil, 200, &meta)
				if !meta.Supported {
					t.Fatal(meta.Reason)
				}
				changes := []editChange{{Op: "update", RowRef: meta.RowRefs[0], Values: map[string]*string{"1": editString("changed")}}}
				if fault == "rollback" {
					changes = append(changes, editChange{Op: "insert", Values: map[string]*string{"0": editString("1"), "1": editString("duplicate")}})
				}
				var plan struct {
					PlanID string `json:"plan_id"`
				}
				h.request("POST", base+"/results/"+rid+"/edits/preview", owner.Token, editPreviewRequest{Changes: changes}, 200, &plan)
				server.mu.RLock()
				s := server.sessions[tokenKey(owner.Token)]
				server.mu.RUnlock()
				s.mu.Lock()
				c := s.consoles[cv.ID]
				s.mu.Unlock()
				c.mu.Lock()
				faultDB := sql.OpenDB(&editFaultConnector{conn: c.conn, fault: fault})
				wrapped, err := faultDB.Conn(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				c.conn = wrapped
				c.mu.Unlock()
				defer faultDB.Close()
				applyPath := base + "/edits/" + plan.PlanID + "/apply"
				requestID := integrationKey()
				var execution ExecutionView
				h.request("POST", applyPath, owner.Token, map[string]any{"request_id": requestID}, 202, &execution)
				done := h.wait(owner, execution.ID, "unknown")
				if done.Edit == nil || done.Edit.Outcome != "unknown" || done.Edit.DatabaseCommitted {
					t.Fatal("lost acknowledgement was reported as a confirmed outcome")
				}
				want := "changed"
				if fault == "rollback" {
					want = "original"
				}
				var got string
				if err := observer.QueryRow("SELECT title FROM " + table).Scan(&got); err != nil || got != want {
					t.Fatal("actual database outcome did not match injected acknowledgement loss")
				}
				// The session retains the exact execution outcome even though its
				// physical console is discarded. No automatic resubmission is made.
				var recovered ExecutionView
				h.request("GET", "/executions/"+done.ID, owner.Token, nil, 200, &recovered)
				if recovered.Status != "unknown" || !c.lost.Load() {
					t.Fatal("uncertain connection was reused or outcome lost")
				}
				t.Log("lost", fault, "acknowledgement remains unknown; database independently verified; physical connection discarded")
			})
		}
	}
}
