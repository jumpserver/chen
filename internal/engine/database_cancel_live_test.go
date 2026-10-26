package engine

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/standalone"
)

func readonlyBackends(t *testing.T) []standalone.BackendConfig {
	t.Helper()
	path := os.Getenv("CHEN_READONLY_BACKENDS")
	if path == "" {
		t.Skip("explicit private read-only test fixture not supplied")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private fixture configuration")
	}
	var selected []standalone.BackendConfig
	for _, b := range cfg.Backends {
		if selector := os.Getenv("CHEN_READONLY_SELECTOR"); selector != "" && b.Selector != selector {
			continue
		}
		selected = append(selected, b)
	}
	return selected
}
func readonlyDriverConfig(b standalone.BackendConfig) jumpserver.ConnectionConfig {
	return jumpserver.ConnectionConfig{Dialect: b.Protocol, Host: b.Host, Port: b.Port, User: b.User, Password: b.Password, Database: b.Database, Schema: b.Schema, Transport: b.Transport, TLS: jumpserver.TLSConfig{Enabled: b.UseSSL, InsecureSkipVerify: b.AllowInvalidCert}}
}
func readonlySafeError(b standalone.BackendConfig, err error) string {
	if err == nil {
		return "<nil>"
	}
	msg := err.Error()
	for _, s := range []string{b.Password, b.Host, b.User, b.Database} {
		if s != "" {
			msg = strings.ReplaceAll(msg, s, "[redacted]")
		}
	}
	return msg
}

// Every statement only suspends this new connection. No application rows,
// schema changes or stored objects are created. This is driver evidence, not
// an assertion that the SELECT-only HTTP executor permits PL/SQL or WAITFOR.
func TestReadonlyDriverCancellation(t *testing.T) {
	for _, b := range readonlyBackends(t) {
		var waitSQL, valueSQL string
		switch b.Protocol {
		case "sqlserver":
			waitSQL, valueSQL = "WAITFOR DELAY '00:00:04'", "SELECT 1"
		case "oracle":
			waitSQL, valueSQL = "BEGIN DBMS_LOCK.SLEEP(4); END;", "SELECT 1 FROM DUAL"
		case "dameng":
			waitSQL, valueSQL = "BEGIN DBMS_LOCK.SLEEP(4); END;", "SELECT 1 FROM DUAL"
		default:
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			db, err := openDatabase(readonlyDriverConfig(b), 1)
			if err != nil {
				t.Fatal(readonlySafeError(b, err))
			}
			defer db.Close()
			conn, err := db.Conn(context.Background())
			if err != nil {
				t.Fatal(readonlySafeError(b, err))
			}
			defer conn.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			started := time.Now()
			go func() { _, err := conn.ExecContext(ctx, waitSQL); done <- err }()
			select {
			case err = <-done:
				if b.Protocol == "oracle" && err != nil && strings.Contains(err.Error(), "PLS-00201") {
					t.Skip("Oracle fixture does not grant DBMS_LOCK.SLEEP; no server-side cancellation claim")
				}
				t.Fatalf("wait command returned before cancellation: %s", readonlySafeError(b, err))
			case <-time.After(500 * time.Millisecond):
			}
			canceledAt := time.Now()
			cancel()
			select {
			case err = <-done:
			case <-time.After(6 * time.Second):
				t.Fatal("driver did not return after cancellation")
			}
			if err == nil && b.Protocol != "dameng" {
				t.Fatal("driver returned success after cancellation")
			}
			if b.Protocol == "dameng" && supportsActiveCancellation(b.Protocol) {
				t.Fatal("driver that cannot stop the wait advertises cancellation")
			}
			t.Logf("driver cancellation latency=%s total=%s error=%s", time.Since(canceledAt).Round(time.Millisecond), time.Since(started).Round(time.Millisecond), readonlySafeError(b, err))
			var value int
			retryCtx, stop := context.WithTimeout(context.Background(), 2*time.Second)
			defer stop()
			retryErr := conn.QueryRowContext(retryCtx, valueSQL).Scan(&value)
			t.Logf("same physical connection reusable=%v error=%s", retryErr == nil && value == 1, readonlySafeError(b, retryErr))
			if b.Protocol != "dameng" && (retryErr != nil || value != 1) {
				t.Fatal("driver lost connection after cancellation")
			}
		})
	}
}

func TestReadonlyHTTPCancellation(t *testing.T) {
	for _, b := range readonlyBackends(t) {
		var waitSQL, idSQL string
		marker := "chen_readonly_cancel_" + integrationKey()
		switch b.Protocol {
		case "mysql", "mariadb", "oceanbase-mysql":
			waitSQL = "SELECT /* " + marker + " */ SLEEP(5),1 UNION ALL SELECT 0,2"
			idSQL = "SELECT CONNECTION_ID()"
		case "postgresql":
			waitSQL = "SELECT /* " + marker + " */ pg_sleep(5)"
			idSQL = "SELECT pg_backend_pid()"
		case "clickhouse":
			waitSQL = "SELECT /* " + marker + " */ sleepEachRow(0.1) FROM numbers(30) SETTINGS max_block_size=1"
			idSQL = "SELECT 1"
		default:
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			provider := singleIntegrationProvider(t, os.Getenv("CHEN_READONLY_BACKENDS"), filepath.Join(t.TempDir(), "audit"))
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 15 * time.Second})
			httpServer := httptest.NewServer(server.Handler())
			defer httpServer.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 20 * time.Second}}
			owner := singleIntegrationSession(h, b.Selector, "readonly-cancel-"+integrationKey())
			con := h.console(owner, b.Database)
			original := h.query(owner, con, idSQL)
			backendID, _ := strconv.ParseInt(fmt.Sprint(original.Rows[0][0]), 10, 64)
			observer, err := openDatabase(readonlyDriverConfig(b), 1)
			if err != nil {
				t.Fatal(readonlySafeError(b, err))
			}
			defer observer.Close()
			long := h.submit(owner, con, waitSQL, integrationKey())
			observe := func(active bool) {
				if b.Protocol != "clickhouse" {
					dialect := "mysql"
					if b.Protocol == "postgresql" {
						dialect = b.Protocol
					}
					waitActiveStatement(t, observer, dialect, backendID, marker, active)
					return
				}
				deadline := time.Now().Add(2 * time.Second)
				for time.Now().Before(deadline) {
					var count int
					err := observer.QueryRow("SELECT count() FROM system.processes WHERE startsWith(query, ?)", "SELECT /* "+marker+" */").Scan(&count)
					if err != nil {
						t.Fatal(readonlySafeError(b, err))
					}
					if (count > 0) == active {
						return
					}
					time.Sleep(20 * time.Millisecond)
				}
				t.Fatalf("server query active=%v was not observed", active)
			}
			observe(true)
			started := time.Now()
			h.request("POST", "/executions/"+long.ID+"/cancel", owner.Token, nil, 202, nil)
			view := h.waitStatuses(owner, long.ID, "canceled", "unknown", "failed")
			observe(false)
			server.mu.Lock()
			liveSession := server.sessions[tokenKey(owner.Token)]
			server.mu.Unlock()
			liveSession.mu.Lock()
			liveConsole := liveSession.consoles[con.ID]
			liveSession.mu.Unlock()
			liveConsole.mu.Lock()
			lost := liveConsole.lost.Load()
			liveConsole.mu.Unlock()
			t.Logf("HTTP cancel terminal=%s elapsed=%s connection_lost=%v", view.Status, time.Since(started).Round(time.Millisecond), lost)
			if view.Status != "canceled" {
				t.Errorf("server-confirmed cancel was not classified canceled")
			}
			if lost {
				t.Errorf("cancellation lost console")
				return
			}
			resumed := h.query(owner, con, idSQL)
			if b.Protocol != "clickhouse" && fmt.Sprint(resumed.Rows[0][0]) != fmt.Sprint(original.Rows[0][0]) {
				t.Fatal("cancellation silently replaced the physical connection")
			}
		})
	}
}

func TestReadonlyClickHouseServerCancellation(t *testing.T) {
	for _, b := range readonlyBackends(t) {
		if b.Protocol != "clickhouse" {
			continue
		}
		t.Logf("ClickHouse fixture protocol port=%d transport=%s", b.Port, b.Transport)
		db, err := openDatabase(readonlyDriverConfig(b), 2)
		if err != nil {
			t.Fatal(readonlySafeError(b, err))
		}
		defer db.Close()
		conn, err := db.Conn(context.Background())
		if err != nil {
			t.Fatal(readonlySafeError(b, err))
		}
		defer conn.Close()
		id := "chen_cancel_" + integrationKey()
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		done := make(chan error, 1)
		go func() {
			rows, err := conn.QueryContext(executionContext(ctx, "clickhouse", id), "SELECT sleepEachRow(0.1) FROM numbers(30) SETTINGS max_block_size=1")
			if err == nil {
				for rows.Next() {
				}
				err = rows.Err()
				rows.Close()
			}
			done <- err
		}()
		deadline := time.Now().Add(2 * time.Second)
		active := false
		for time.Now().Before(deadline) {
			var count int
			if err := db.QueryRow("SELECT count() FROM system.processes WHERE query_id = ?", id).Scan(&count); err != nil {
				t.Fatal(readonlySafeError(b, err))
			}
			if count == 1 {
				active = true
				break
			}
			time.Sleep(20 * time.Millisecond)
		}
		if !active {
			t.Fatal("server did not observe the exact query ID")
		}
		started := time.Now()
		controlCtx, stop := context.WithTimeout(context.Background(), 2*time.Second)
		defer stop()
		if err := cancelClickHouseExecution(controlCtx, db, id); err != nil {
			t.Fatal(readonlySafeError(b, err))
		}
		select {
		case err = <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("query did not stop after KILL")
		}
		if !isServerCancellation(err) {
			t.Fatalf("cancel not server confirmed: %s", readonlySafeError(b, err))
		}
		var one int
		if err := conn.QueryRowContext(context.Background(), "SELECT 1").Scan(&one); err != nil || one != 1 {
			t.Fatal("same ClickHouse connection not reusable after KILL")
		}
		t.Logf("server confirmed cancellation in %s; same physical connection reusable", time.Since(started).Round(time.Millisecond))
	}
}

// This checks Chen's timeout and administrative session-close paths against a
// real ClickHouse server. It does not claim a live Core/Wisp control test.
func TestReadonlyClickHouseAutomaticCancellation(t *testing.T) {
	for _, b := range readonlyBackends(t) {
		if b.Protocol != "clickhouse" {
			continue
		}
		for _, mode := range []string{"timeout", "admin_close"} {
			t.Run(mode, func(t *testing.T) {
				timeout := 15 * time.Second
				if mode == "timeout" {
					timeout = time.Second
				}
				provider := singleIntegrationProvider(t, os.Getenv("CHEN_READONLY_BACKENDS"), filepath.Join(t.TempDir(), "audit"))
				server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: timeout})
				httpServer := httptest.NewServer(server.Handler())
				defer httpServer.Close()
				defer server.Close()
				h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 10 * time.Second}}
				owner := singleIntegrationSession(h, b.Selector, "readonly-auto-cancel-"+integrationKey())
				con := h.console(owner, b.Database)
				observer, err := openDatabase(readonlyDriverConfig(b), 1)
				if err != nil {
					t.Fatal(readonlySafeError(b, err))
				}
				defer observer.Close()
				long := h.submit(owner, con, "SELECT sleepEachRow(0.1) FROM numbers(50) SETTINGS max_block_size=1", integrationKey())
				observe := func(active bool) {
					deadline := time.Now().Add(3 * time.Second)
					for time.Now().Before(deadline) {
						var count int
						ctx, stop := context.WithTimeout(context.Background(), time.Second)
						err := observer.QueryRowContext(ctx, "SELECT count() FROM system.processes WHERE query_id = ?", long.ID).Scan(&count)
						stop()
						if err != nil {
							t.Fatal(readonlySafeError(b, err))
						}
						if (count > 0) == active {
							return
						}
						time.Sleep(20 * time.Millisecond)
					}
					t.Fatalf("server query active=%v was not observed", active)
				}
				observe(true)
				server.mu.Lock()
				s := server.sessions[tokenKey(owner.Token)]
				server.mu.Unlock()
				s.mu.Lock()
				e := s.executions[long.ID]
				s.mu.Unlock()
				if e == nil {
					t.Fatal("accepted execution missing")
				}
				started := time.Now()
				if mode == "admin_close" {
					server.closeSession(s, "admin_close")
				}
				observe(false)
				deadline := time.Now().Add(3 * time.Second)
				for time.Now().Before(deadline) {
					view := e.snapshot()
					if terminal(view.Status) && view.AuditStatus == "recorded" {
						if view.Status == "succeeded" {
							t.Fatal("interrupted server query falsely reported success")
						}
						t.Logf("%s removed exact server query in %s; terminal=%s audit=%s", mode, time.Since(started).Round(time.Millisecond), view.Status, view.AuditStatus)
						return
					}
					time.Sleep(20 * time.Millisecond)
				}
				t.Fatal("interrupted execution did not finish and persist its audit")
			})
		}
	}
}
