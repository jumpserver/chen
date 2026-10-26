package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

func catalogFixture(t *testing.T) (*Server, *session, *catalogResource) {
	t.Helper()
	p := &executionProvider{}
	v := New(p, Options{})
	s := seedEngineSession(v, "token")
	s.grant.Config.Dialect = "postgresql"
	s.grant.Config.Database = "app"
	state := &executionDriverState{query: func(_ context.Context, q string) (driver.Rows, error) {
		if q != "SELECT pg_catalog.current_database()" {
			return nil, errors.New("unexpected SQL; no user query may execute")
		}
		return &executionRows{columns: []string{"database"}, types: []string{"TEXT"}, values: [][]driver.Value{{"app"}}}, nil
	}}
	db := sql.OpenDB(executionConnector{state})
	db.SetMaxOpenConns(2)
	resource := &catalogResource{db: db, slots: make(chan struct{}, 2)}
	resource.cursorKey[0] = 42
	s.catalog = resource
	t.Cleanup(v.Close)
	return v, s, resource
}
func catalogHTTPRequest(path string) *httpRequestShim { return &httpRequestShim{path: path} }

// beginCatalog is exercised directly so a deliberately blocking SQL console
// cannot be hidden by a mock of the low-level metadata implementation.
type httpRequestShim struct{ path string }

func (shim *httpRequestShim) begin(v *Server, operation string) (*catalogRequest, *httptest.ResponseRecorder) {
	r := engineRequest("GET", shim.path, "", "token")
	r.SetPathValue("sid", "ses_test")
	w := httptest.NewRecorder()
	return v.beginCatalog(w, r, operation), w
}

func TestCatalogUsesIndependentConnectionWhileConsoleBusy(t *testing.T) {
	v, s, resource := catalogFixture(t)
	ctx, cancel := context.WithCancel(s.ctx)
	c := &console{id: "con_busy", ctx: ctx, cancel: cancel}
	s.consoles[c.id] = c
	c.mu.Lock()
	req, w := catalogHTTPRequest("/chen/api/v1/sessions/ses_test/catalog/schemas").begin(v, "schemas")
	c.mu.Unlock()
	if req == nil {
		t.Fatalf("busy SQL console blocked independent catalog: HTTP%d %s", w.Code, w.Body.String())
	}
	defer req.done()
	if req.database != "app" || resource.db.Stats().InUse != 1 {
		t.Fatal("catalog did not pin its own authenticated database connection")
	}
	if s.db != nil {
		t.Fatal("fixture unexpectedly borrowed the query/control pool")
	}
}

func TestCatalogConcurrencyReturnsBusyWithoutWaiting(t *testing.T) {
	v, _, resource := catalogFixture(t)
	one, _ := catalogHTTPRequest("/catalog").begin(v, "schemas")
	two, _ := catalogHTTPRequest("/catalog").begin(v, "schemas")
	if one == nil || two == nil {
		t.Fatal("two bounded catalog operations should be allowed")
	}
	defer one.done()
	defer two.done()
	start := time.Now()
	third, w := catalogHTTPRequest("/catalog").begin(v, "schemas")
	if third != nil || w.Code != 429 || time.Since(start) > time.Second {
		t.Fatalf("catalog bound failed: HTTP%d", w.Code)
	}
	if resource.db.Stats().InUse != 2 {
		t.Fatal("throttled request opened an extra connection")
	}
}

func TestCatalogGlobalConcurrencyDoesNotUseControlConnections(t *testing.T) {
	v, _, resource := catalogFixture(t)
	for i := 0; i < cap(v.catalogSlots); i++ {
		v.catalogSlots <- struct{}{}
	}
	req, w := catalogHTTPRequest("/catalog").begin(v, "schemas")
	for i := 0; i < cap(v.catalogSlots); i++ {
		<-v.catalogSlots
	}
	if req != nil || w.Code != 429 || resource.db.Stats().OpenConnections != 0 {
		t.Fatal("global catalog limit was bypassed or borrowed another pool")
	}
}

func TestCatalogCloseCancelsAndWaitsForAuditOwner(t *testing.T) {
	v, s, resource := catalogFixture(t)
	req, _ := catalogHTTPRequest("/catalog").begin(v, "schemas")
	if req == nil {
		t.Fatal("catalog begin failed")
	}
	v.closeSession(s, "user_closed")
	select {
	case <-req.ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("session closure did not cancel catalog context")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := v.CloseContext(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("close skipped an in-flight catalog audit/connection: %v", err)
	}
	req.done()
	ctx2, cancel2 := context.WithTimeout(context.Background(), time.Second)
	defer cancel2()
	if err := v.CloseContext(ctx2); err != nil {
		t.Fatal(err)
	}
	if resource.db.Stats().OpenConnections != 0 {
		t.Fatal("closed session leaked catalog connections")
	}
}

func TestCatalogCursorBoundToSessionDatabaseAndFilter(t *testing.T) {
	_, _, resource := catalogFixture(t)
	scope := catalogScope{operation: "objects", schema: "public", kind: "table", query: "user", limit: 100}
	cursor := resource.encodeCursor("session-one", "app", scope, "public", "users", 0)
	value, err := resource.decodeCursor(cursor, "session-one", "app", scope)
	if err != nil || value.AfterName != "users" {
		t.Fatal("valid keyset cursor failed")
	}
	for _, change := range []func(*catalogScope){func(s *catalogScope) { s.schema = "private" }, func(s *catalogScope) { s.kind = "view" }, func(s *catalogScope) { s.query = "other" }, func(s *catalogScope) { s.operation = "columns" }, func(s *catalogScope) { s.table = "private" }} {
		changed := scope
		change(&changed)
		if _, err := resource.decodeCursor(cursor, "session-one", "app", changed); err == nil {
			t.Fatal("cursor crossed a query scope")
		}
	}
	if _, err := resource.decodeCursor(cursor, "session-two", "app", scope); err == nil {
		t.Fatal("cursor crossed sessions")
	}
	if _, err := resource.decodeCursor(cursor, "session-one", "otherdb", scope); err == nil {
		t.Fatal("cursor crossed databases")
	}
	if _, err := resource.decodeCursor("A"+cursor[1:], "session-one", "app", scope); err == nil {
		t.Fatal("tampered cursor was accepted")
	}
}

func TestCatalogRejectsCrossDatabaseAndInvalidPaginationBeforeConnecting(t *testing.T) {
	v, _, resource := catalogFixture(t)
	for _, path := range []string{"/catalog?database=other", "/catalog?limit=0", "/catalog?limit=oops", "/catalog?kind=sequence", "/catalog?schema=one&schema=two", "/catalog?q=%00", "/catalog?q=%ff"} {
		req, w := catalogHTTPRequest(path).begin(v, "objects")
		if req != nil || w.Code != 400 {
			t.Fatalf("accepted invalid catalog request %s HTTP%d", path, w.Code)
		}
	}
	if resource.db.Stats().OpenConnections != 0 {
		t.Fatal("invalid request opened a database connection")
	}
	r := engineRequest("GET", "/catalog?limit=9999", "", "")
	scope, err := parseCatalogScope(r, "objects")
	if err != nil || scope.limit != 200 {
		t.Fatal("catalog page limit is not bounded")
	}
}

type failingCatalogAudit struct{ *executionProvider }

func (p *failingCatalogAudit) Record(context.Context, *jumpserver.Grant, jumpserver.AuditRecord) error {
	return jumpserver.ErrAuditDisk
}
func TestCatalogAuditFailureReleasesResourcesAndFailsClosed(t *testing.T) {
	v, _, resource := catalogFixture(t)
	v.provider = &failingCatalogAudit{v.provider.(*executionProvider)}
	req, w := catalogHTTPRequest("/catalog").begin(v, "schemas")
	if req != nil || w.Code != 503 || resource.db.Stats().InUse != 0 || len(resource.slots) != 0 || len(v.catalogSlots) != 0 {
		t.Fatal("audit failure leaked an operation or permitted catalog access")
	}
}

type contextCheckingCatalogAudit struct{ *executionProvider }

func (p *contextCheckingCatalogAudit) Record(ctx context.Context, grant *jumpserver.Grant, record jumpserver.AuditRecord) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > 4*time.Second {
		return errors.New("catalog audit must have its own bounded deadline")
	}
	return p.executionProvider.Record(ctx, grant, record)
}

func TestCatalogCanceledQueryStillPersistsResultWithoutMaskingError(t *testing.T) {
	for _, tc := range []struct {
		name   string
		closed bool
		status int
		code   string
	}{{"timeout", false, 504, "catalog_timeout"}, {"request cancellation", false, 408, "request_canceled"}, {"session closure", true, 410, "session_closed"}} {
		t.Run(tc.name, func(t *testing.T) {
			v, s, _ := catalogFixture(t)
			p := &contextCheckingCatalogAudit{v.provider.(*executionProvider)}
			v.provider = p
			req, _ := catalogHTTPRequest("/catalog").begin(v, "schemas")
			if req == nil {
				t.Fatal("catalog begin failed")
			}
			defer req.done()
			r := engineRequest("GET", "/catalog", "", "token")
			if tc.name == "timeout" {
				ctx, cancel := context.WithDeadline(req.ctx, time.Now().Add(-time.Second))
				defer cancel()
				req.ctx = ctx
			} else {
				ctx, cancel := context.WithCancel(req.ctx)
				cancel()
				req.ctx = ctx
				r = r.WithContext(ctx)
			}
			if tc.closed {
				v.closeSession(s, "user_closed")
			}
			w := httptest.NewRecorder()
			if v.finishCatalog(w, r, req, 0, nil) || w.Code != tc.status || !strings.Contains(w.Body.String(), tc.code) {
				t.Fatalf("canceled catalog error was lost: HTTP%d %s", w.Code, w.Body.String())
			}
			p.mu.Lock()
			defer p.mu.Unlock()
			if len(p.records) != 2 || !strings.Contains(p.records[1].Output, "outcome=failed") {
				t.Fatal("canceled catalog result was not durably audited")
			}
		})
	}
}

func TestCatalogAuthorizationAndSafeFailureClassification(t *testing.T) {
	v, s, resource := catalogFixture(t)
	r := engineRequest("GET", "/chen/api/v1/sessions/not-the-owner/catalog/schemas", "", "token")
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, r)
	if w.Code != 404 || resource.db.Stats().OpenConnections != 0 {
		t.Fatal("session ownership did not gate catalog access")
	}
	for _, tc := range []struct {
		err    error
		status int
		code   string
	}{{sqlservice.ErrCatalogForbidden, 403, "catalog_forbidden"}, {sqlservice.ErrCatalogNotFound, 404, "catalog_not_found"}, {context.DeadlineExceeded, 504, "catalog_timeout"}, {errors.New("database password sensitive-value"), 502, "catalog_failed"}} {
		w := httptest.NewRecorder()
		catalogError(w, engineRequest("GET", "/catalog", "", "token"), s, tc.err)
		if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.code) || strings.Contains(w.Body.String(), "sensitive-value") {
			t.Fatal("catalog error classification leaked data or lost its permission distinction")
		}
	}
}
