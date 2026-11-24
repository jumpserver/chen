package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

// Answer the initial connection-context query. The first catalog query then
// waits as a slow database would until the request or session is canceled.
type slowCatalogDriver struct {
	driver.Conn
	read func(context.Context) error
}

func (d *slowCatalogDriver) Driver() driver.Driver                        { return nil }
func (d *slowCatalogDriver) Connect(context.Context) (driver.Conn, error) { return d, nil }
func (d *slowCatalogDriver) Close() error                                 { return nil }
func (d *slowCatalogDriver) QueryContext(ctx context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
	switch query {
	case "SELECT COALESCE(DATABASE(),'')":
		return &catalogScopeRow{names: []string{"database"}}, nil
	case "SELECT current_database(), COALESCE(current_schema(),'')":
		return &catalogScopeRow{names: []string{"database", "schema"}}, nil
	}
	return nil, d.read(ctx)
}

type catalogScopeRow struct {
	names []string
	read  bool
}

func (r *catalogScopeRow) Columns() []string { return r.names }
func (r *catalogScopeRow) Close() error      { return nil }
func (r *catalogScopeRow) Next(values []driver.Value) error {
	if r.read {
		return io.EOF
	}
	r.read = true
	for i := range values {
		values[i] = "demo"
	}
	return nil
}

type catalogAuditProvider struct{ jumpserver.Provider }

func (catalogAuditProvider) Record(context.Context, *jumpserver.Grant, jumpserver.AuditRecord) error {
	return nil
}

func TestCatalogQueriesInheritRequestLifetime(t *testing.T) {
	for _, tc := range []struct{ dialect, path string }{
		{"postgresql", "databases"},
		{"mysql", "schemas"},
		{"mysql", "objects"},
		{"mysql", "columns?table=example"},
		{"mysql", "inspect?table=example"},
		{"mysql", "ddl?table=example"},
		{"mysql", "overview?database=demo&section=tables"},
		{"mysql", "diagram?database=demo&section=nodes&direction=schema"},
	} {
		t.Run(tc.path, func(t *testing.T) {
			sessionCtx, closeSession := context.WithCancel(t.Context())
			defer closeSession()
			requestCtx, cancelRequest := context.WithCancel(t.Context())
			defer cancelRequest()
			var wantDeadline time.Time
			cancel := cancelRequest
			wantStatus := http.StatusRequestTimeout
			if tc.dialect == "postgresql" {
				wantDeadline = time.Now().Add(5 * time.Minute)
				var release context.CancelFunc
				requestCtx, release = context.WithDeadline(requestCtx, wantDeadline)
				defer release()
				cancel = closeSession
				wantStatus = http.StatusGone
			}
			queried := false
			db := sql.OpenDB(&slowCatalogDriver{read: func(ctx context.Context) error {
				queried = true
				if deadline, _ := ctx.Deadline(); !deadline.Equal(wantDeadline) {
					t.Errorf("catalog query deadline = %v, want %v", deadline, wantDeadline)
				}
				cancel()
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-time.After(time.Second):
					t.Error("catalog query did not receive cancellation")
					return errors.New("query still running")
				}
			}})
			defer db.Close()
			s := &session{
				id: "s", ctx: sessionCtx,
				grant:   &jumpserver.Grant{Config: jumpserver.ConnectionConfig{Dialect: tc.dialect}},
				catalog: &catalogResource{db: db, slots: make(chan struct{}, 2)},
			}
			v := &Server{
				sessions:     map[[32]byte]*session{tokenKey("token"): s},
				catalogSlots: make(chan struct{}, 1),
				provider:     catalogAuditProvider{},
			}
			req := httptest.NewRequestWithContext(requestCtx, http.MethodGet, "/chen/api/v1/sessions/s/catalog/"+tc.path, nil)
			req.Header.Set("Authorization", "Bearer token")
			response := httptest.NewRecorder()
			v.Handler().ServeHTTP(response, req)
			if !queried || response.Code != wantStatus {
				t.Errorf("queried = %t, HTTP %d, want HTTP %d: %s", queried, response.Code, wantStatus, response.Body.String())
			}
			if len(v.catalogSlots) != 0 || len(s.catalog.slots) != 0 {
				t.Error("catalog slot still occupied after cancellation")
			}
		})
	}
}
