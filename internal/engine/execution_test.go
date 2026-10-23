package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jumpserver/chen/internal/jumpserver"
)

// A stateful database/sql driver models unknown server outcomes and control
// connections without starting another database alongside live acceptance tests.
type executionDriverState struct {
	mu       sync.Mutex
	commands []string
	exec     func(context.Context, string) (driver.Result, error)
	query    func(context.Context, string) (driver.Rows, error)
	ping     func(context.Context) error
}
type executionConnector struct{ state *executionDriverState }

func (c executionConnector) Connect(context.Context) (driver.Conn, error) {
	return &executionConn{c.state}, nil
}
func (c executionConnector) Driver() driver.Driver { return executionDriver{c.state} }

type executionDriver struct{ state *executionDriverState }

func (d executionDriver) Open(string) (driver.Conn, error) { return &executionConn{d.state}, nil }

type executionConn struct{ state *executionDriverState }

func (c *executionConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("test driver requires context methods")
}
func (c *executionConn) Close() error { return nil }
func (c *executionConn) Begin() (driver.Tx, error) {
	return nil, errors.New("test driver does not start transactions")
}
func (c *executionConn) Ping(ctx context.Context) error {
	if c.state.ping != nil {
		return c.state.ping(ctx)
	}
	return nil
}
func (c *executionConn) ExecContext(ctx context.Context, q string, _ []driver.NamedValue) (driver.Result, error) {
	c.state.mu.Lock()
	c.state.commands = append(c.state.commands, q)
	c.state.mu.Unlock()
	if c.state.exec != nil {
		return c.state.exec(ctx, q)
	}
	return driver.RowsAffected(1), nil
}
func (c *executionConn) QueryContext(ctx context.Context, q string, _ []driver.NamedValue) (driver.Rows, error) {
	if q == "SELECT @@session.sql_mode, VERSION()" {
		return &executionRows{columns: []string{"sql_mode", "version"}, types: []string{"VARCHAR", "VARCHAR"}, values: [][]driver.Value{{"", "8.0.46"}}}, nil
	}
	if c.state.query != nil {
		return c.state.query(ctx, q)
	}
	return &executionRows{columns: []string{"value"}, types: []string{"BIGINT"}, values: [][]driver.Value{{int64(1)}}}, nil
}

type executionRows struct {
	columns, types []string
	values         [][]driver.Value
	index          int
	failure        error
}

func (r *executionRows) Columns() []string                       { return r.columns }
func (r *executionRows) ColumnTypeDatabaseTypeName(i int) string { return r.types[i] }
func (r *executionRows) Close() error                            { return nil }
func (r *executionRows) Next(dst []driver.Value) error {
	if r.index < len(r.values) {
		copy(dst, r.values[r.index])
		r.index++
		return nil
	}
	if r.failure != nil {
		err := r.failure
		r.failure = nil
		return err
	}
	return io.EOF
}

type executionProvider struct {
	jumpserver.Provider
	mu              sync.Mutex
	records         []jumpserver.AuditRecord
	hideDiagnostics bool
}

func (p *executionProvider) Authorize(context.Context, *jumpserver.Grant, string) error { return nil }
func (p *executionProvider) Record(_ context.Context, _ *jumpserver.Grant, r jumpserver.AuditRecord) error {
	p.mu.Lock()
	p.records = append(p.records, r)
	p.mu.Unlock()
	return nil
}
func (p *executionProvider) Mask(_ *jumpserver.Grant, _, _ string, value any) (any, bool) {
	if p.hideDiagnostics {
		return "####", true
	}
	return value, false
}
func (p *executionProvider) Close(context.Context, *jumpserver.Grant) error { return nil }

func executionFixture(t *testing.T, dialect, command string, state *executionDriverState) (*Server, *session, *console, *execution) {
	t.Helper()
	db := sql.OpenDB(executionConnector{state})
	db.SetMaxOpenConns(4)
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	v := New(&executionProvider{}, Options{StateDir: t.TempDir(), MaxRows: 100, MaxResultBytes: 4096})
	s := seedEngineSession(v, "token")
	s.db = db
	s.grant.Config.Dialect = dialect
	ctx, cancel := context.WithCancel(s.ctx)
	c := &console{id: "con_test", conn: conn, ctx: ctx, cancel: cancel, backendID: 42, queue: make(chan *execution, 8), requests: map[string]*execution{}}
	s.consoles[c.id] = c
	ectx, ecancel := context.WithCancel(c.ctx)
	e := &execution{view: ExecutionView{ID: "exe_test", ConsoleID: c.id, Status: "queued", ResultIDs: []string{}, StatementResults: []StatementResult{}}, sql: command, ctx: ectx, cancel: ecancel, cancelDone: make(chan struct{})}
	s.executions[e.view.ID] = e
	t.Cleanup(v.Close)
	return v, s, c, e
}

func TestMySQLContextCancellationJoinsBackendKillBeforeReleasingConsole(t *testing.T) {
	started, killStarted, releaseKill := make(chan struct{}), make(chan struct{}), make(chan struct{})
	state := &executionDriverState{exec: func(ctx context.Context, q string) (driver.Result, error) {
		if strings.HasPrefix(q, "KILL QUERY ") {
			close(killStarted)
			<-releaseKill
			return driver.RowsAffected(0), nil
		}
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	v, s, c, e := executionFixture(t, "mysql", "UPDATE example SET value = 1", state)
	done := make(chan struct{})
	go func() { v.run(s, c, e); close(done) }()
	<-started
	e.cancel()
	select {
	case <-killStarted:
	case <-time.After(time.Second):
		t.Fatal("context cancellation closed only the client, without sending a server KILL")
	}
	if c.mu.TryLock() {
		c.mu.Unlock()
		t.Fatal("console was released before server cancellation completed")
	}
	close(releaseKill)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("execution did not join server cancellation")
	}
	if e.snapshot().Status != "unknown" {
		t.Fatal("client cancellation was falsely treated as proof of the database outcome")
	}
}

func TestNormalMySQLCompletionDoesNotKillNextStatement(t *testing.T) {
	killed := make(chan struct{}, 1)
	state := &executionDriverState{exec: func(_ context.Context, q string) (driver.Result, error) {
		if strings.HasPrefix(q, "KILL QUERY ") {
			killed <- struct{}{}
		}
		return driver.RowsAffected(1), nil
	}}
	v, s, c, e := executionFixture(t, "mysql", "UPDATE example SET value = 1", state)
	v.run(s, c, e)
	if e.snapshot().Status != "succeeded" {
		t.Fatalf("normal execution = %s", e.snapshot().Status)
	}
	select {
	case <-killed:
		t.Fatal("normal completion canceled its context before stopping the KILL callback")
	case <-time.After(20 * time.Millisecond):
	}
}

func TestPartialScriptCancellationPreservesConfirmedPrefix(t *testing.T) {
	var e *execution
	state := &executionDriverState{exec: func(context.Context, string) (driver.Result, error) { e.cancel(); return driver.RowsAffected(1), nil }}
	v, s, c, created := executionFixture(t, "postgresql", "INSERT INTO example VALUES (1); INSERT INTO example VALUES (2)", state)
	e = created
	v.run(s, c, e)
	view := e.snapshot()
	if view.Status != "canceled" || view.Error == nil || view.Error.Code != "script_partial_cancel" || len(view.StatementResults) != 1 {
		t.Fatalf("known prefix incorrectly classified: %+v", view)
	}
	if !strings.Contains(view.Error.Message, "1 earlier statement(s) completed") {
		t.Fatal("partial completion warning was omitted")
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.commands) != 1 {
		t.Fatal("script suffix was dispatched after cancellation")
	}
}

func TestUnstructuredWriteFailureHasUnknownOutcome(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
		want string
	}{
		{"driver bad connection", driver.ErrBadConn, "unknown"},
		{"mysql invalid connection", mysql.ErrInvalidConn, "unknown"},
		{"closed sql connection", sql.ErrConnDone, "unknown"},
		{"generic read error", errors.New("unexpected driver failure"), "unknown"},
		{"server SQL failure", &pgconn.PgError{Code: "23505", Message: "duplicate key"}, "failed"},
		{"server cancellation", &pgconn.PgError{Code: "57014", Message: "query canceled"}, "canceled"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			state := &executionDriverState{exec: func(context.Context, string) (driver.Result, error) { return nil, tc.err }}
			v, s, c, e := executionFixture(t, "postgresql", "INSERT INTO example VALUES (1)", state)
			v.run(s, c, e)
			if got := e.snapshot().Status; got != tc.want {
				t.Fatalf("outcome=%s want %s", got, tc.want)
			}
		})
	}
}

func TestNonfiniteReturningValueDoesNotMisreportSuccessfulWrite(t *testing.T) {
	state := &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		return &executionRows{columns: []string{"value"}, types: []string{"FLOAT8"}, values: [][]driver.Value{{math.NaN()}, {math.Inf(1)}, {math.Inf(-1)}}}, nil
	}}
	v, s, c, e := executionFixture(t, "postgresql", "INSERT INTO example VALUES (1) RETURNING 1::float8", state)
	v.run(s, c, e)
	view := e.snapshot()
	if view.Status != "succeeded" || len(view.ResultIDs) != 1 {
		t.Fatalf("successful INSERT RETURNING misreported due to JSON value: %+v", view)
	}
	result := s.results[view.ResultIDs[0]]
	for i, want := range []string{"NaN", "Infinity", "-Infinity"} {
		if result.Rows[i][0] != want {
			t.Fatalf("nonfinite value=%v want %s", result.Rows[i][0], want)
		}
	}
	if _, err := json.Marshal(result); err != nil {
		t.Fatalf("browser result remains invalid JSON: %v", err)
	}
}

func TestServerDiagnosticCannotBypassFieldMasking(t *testing.T) {
	state := &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		return nil, &pgconn.PgError{Code: "22P02", Message: `invalid input syntax for type integer: "protected-secret-value"`}
	}}
	v, s, c, e := executionFixture(t, "postgresql", "SELECT secret::integer FROM example", state)
	provider := v.provider.(*executionProvider)
	provider.hideDiagnostics = true
	v.run(s, c, e)
	view := e.snapshot()
	if view.Status != "failed" || view.Error == nil || view.Error.Code != "22P02" || strings.Contains(view.Error.Message, "protected-secret-value") {
		t.Fatalf("server diagnostic bypassed field masking: %+v", view)
	}
	provider.mu.Lock()
	defer provider.mu.Unlock()
	if len(provider.records) != 1 || strings.Contains(provider.records[0].Error, "protected-secret-value") {
		t.Fatal("audit retained the unmasked server diagnostic")
	}
}

func TestFailedRowStreamReleasesUnpublishedResultBudget(t *testing.T) {
	state := &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		return &executionRows{columns: []string{"value"}, types: []string{"VARCHAR"}, values: [][]driver.Value{{"partial data"}}, failure: io.ErrUnexpectedEOF}, nil
	}}
	v, s, c, _ := executionFixture(t, "postgresql", "SELECT 1", state)
	s.resultBytes = 17
	rows, err := c.conn.QueryContext(context.Background(), "SELECT 1")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	if _, err := v.readRows(s, rows); err == nil {
		t.Fatal("expected row stream failure")
	}
	if s.resultBytes != 17 {
		t.Fatalf("discarded partial result permanently consumed quota: %d", s.resultBytes)
	}
}

func TestFinishedJournalFailureDoesNotChangeSQLSuccess(t *testing.T) {
	state := &executionDriverState{}
	v, s, c, e := executionFixture(t, "postgresql", "INSERT INTO example VALUES (1)", state)
	state.exec = func(context.Context, string) (driver.Result, error) {
		dir := filepath.Join(v.opts.StateDir, "executions")
		if err := os.RemoveAll(dir); err != nil {
			return nil, err
		}
		if err := os.WriteFile(dir, []byte("block future journal writes"), 0600); err != nil {
			return nil, err
		}
		return driver.RowsAffected(1), nil
	}
	v.run(s, c, e)
	view := e.snapshot()
	if view.Status != "succeeded" || view.AuditStatus != "failed" {
		t.Fatalf("finished journal failure changed SQL outcome or disappeared: %+v", view)
	}
}

func TestTemporalValuesDoNotInventTimezoneOrZeroDate(t *testing.T) {
	value := time.Date(2026, 10, 23, 12, 34, 56, 123456000, time.UTC)
	for _, tc := range []struct {
		kind  string
		value time.Time
		want  string
	}{
		{"DATE", value, "2026-10-23"}, {"TIMESTAMP", value, "2026-10-23 12:34:56.123456"}, {"TIMESTAMPTZ", value, "2026-10-23T12:34:56.123456Z"}, {"DATE", time.Time{}, "0001-01-01"},
	} {
		if got := normalizeValue(tc.value, tc.kind); got != tc.want {
			t.Fatalf("%s=%v want %s", tc.kind, got, tc.want)
		}
	}
}

type blockedExecutionBody struct {
	started chan struct{}
	release chan struct{}
	body    io.Reader
	once    sync.Once
}

func (b *blockedExecutionBody) Read(p []byte) (int, error) {
	b.once.Do(func() { close(b.started); <-b.release })
	return b.body.Read(p)
}
func (b *blockedExecutionBody) Close() error { return nil }

func TestSubmitRechecksConsoleAfterRequestBodyRead(t *testing.T) {
	state := &executionDriverState{}
	v, s, c, _ := executionFixture(t, "postgresql", "SELECT 1", state)
	s.executions = map[string]*execution{}
	body := &blockedExecutionBody{started: make(chan struct{}), release: make(chan struct{}), body: strings.NewReader(`{"sql":"SELECT 1","request_id":"stable-request"}`)}
	r := engineRequest("POST", "/chen/api/v1/consoles/"+c.id+"/executions", "", "token")
	r.Body = body
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { v.Handler().ServeHTTP(w, r); close(done) }()
	<-body.started
	closed := httptest.NewRecorder()
	v.Handler().ServeHTTP(closed, engineRequest("DELETE", "/chen/api/v1/consoles/"+c.id, "", "token"))
	close(body.release)
	<-done
	if w.Code != 409 || len(s.executions) != 0 || len(c.queue) != 0 {
		t.Fatalf("deleted console accepted execution with no worker/audit: HTTP %d", w.Code)
	}
}
