package engine

import (
	"context"
	"errors"
	"net/http/httptest"
	"testing"

	"github.com/jumpserver/chen/internal/jumpserver"
)

type readinessProvider struct {
	jumpserver.Provider
	err   error
	calls int
}

func (p *readinessProvider) Ready(context.Context) error { p.calls++; return p.err }

func TestReadinessBoundaries(t *testing.T) {
	for _, single := range []bool{false, true} {
		p := &readinessProvider{err: errors.New("private-address-and-token")}
		v := New(p, Options{SingleMode: single, StateDir: t.TempDir()})
		defer v.Close()
		check := func(path string, status int, body string) {
			t.Helper()
			w := httptest.NewRecorder()
			v.Handler().ServeHTTP(w, httptest.NewRequest("GET", path, nil))
			if w.Code != status || w.Body.String() != body+"\n" {
				t.Fatalf("%s: %d %s", path, w.Code, w.Body.String())
			}
		}
		if single {
			check("/ready", 200, `{"status":"ready"}`)
		} else {
			check("/ready", 503, `{"status":"not_ready"}`)
		}
		check("/healthy", 200, `{"status":"ok"}`)
		p.err = nil
		check("/ready", 200, `{"status":"ready"}`)
		p.err = errors.New("control disconnected")
		if single {
			check("/ready", 200, `{"status":"ready"}`)
		} else {
			check("/ready", 503, `{"status":"not_ready"}`)
		}
		check("/healthy", 200, `{"status":"ok"}`)
		p.err = nil
		check("/ready", 200, `{"status":"ready"}`)
		if single && p.calls != 0 {
			t.Fatal("single readiness consulted Wisp")
		}
		v.Close()
		check("/ready", 503, `{"status":"not_ready"}`)
		check("/healthy", 200, `{"status":"ok"}`)
	}
}
