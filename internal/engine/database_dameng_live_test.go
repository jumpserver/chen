package engine

import (
	"context"
	"database/sql"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	dm "gitee.com/chunanyong/dm"
	"github.com/jumpserver/chen/internal/standalone"
)

func TestDamengSocketTimeoutLive(t *testing.T) {
	const dialName = "chen-dameng-short-timeout-test"
	dm.RegisterDialContext(dialName, damengDial(time.Second, time.Second))
	for _, backend := range readonlyBackends(t) {
		if backend.Protocol != "dameng" {
			continue
		}
		for i := range 3 {
			t.Run(backend.Selector+"/"+strconv.Itoa(i), func(t *testing.T) {
				t.Parallel()
				c := readonlyDriverConfig(backend)
				dsn := "dm://" + c.User + ":" + c.Password + "@" + net.JoinHostPort(c.Host, strconv.Itoa(c.Port)) + "?doSwitch=0&logLevel=off&lobMode=1&dialName=" + dialName
				db, err := openDameng(dsn)
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer db.Close()
				conn, err := db.Conn(context.Background())
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer conn.Close()
				started := time.Now()
				_, err = conn.ExecContext(context.Background(), "BEGIN DBMS_LOCK.SLEEP(2); END;")
				if !errors.Is(err, errDamengTransport) || isServerError(err) || time.Since(started) > 3*time.Second {
					t.Fatalf("read timeout was not bounded and classified as unknown: %s", readonlySafeError(backend, err))
				}
				if err := conn.PingContext(context.Background()); err == nil {
					t.Fatal("timed out transport was reused")
				}
				t.Logf("real network read timeout after %s; transport discarded without retry", time.Since(started).Round(time.Millisecond))
			})
		}
	}
}

func TestDamengDeadlineAndPreparedLive(t *testing.T) {
	for _, backend := range readonlyBackends(t) {
		if backend.Protocol != "dameng" {
			continue
		}
		for _, prepared := range []bool{false, true} {
			name := "direct"
			if prepared {
				name = "prepared"
			}
			t.Run(backend.Selector+"/"+name, func(t *testing.T) {
				db, err := openDatabase(readonlyDriverConfig(backend), 1)
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer db.Close()
				conn, err := db.Conn(context.Background())
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer conn.Close()
				var stmt *sql.Stmt
				if prepared {
					stmt, err = conn.PrepareContext(context.Background(), "BEGIN DBMS_LOCK.SLEEP(2); END;")
					if err != nil {
						t.Fatal(readonlySafeError(backend, err))
					}
					defer stmt.Close()
				}
				ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
				defer cancel()
				start := time.Now()
				if prepared {
					_, err = stmt.ExecContext(ctx)
				} else {
					_, err = conn.ExecContext(ctx, "BEGIN DBMS_LOCK.SLEEP(2); END;")
				}
				if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) < time.Second {
					t.Fatalf("deadline was not drained: elapsed=%s err=%s", time.Since(start), readonlySafeError(backend, err))
				}
				if err := conn.PingContext(context.Background()); err == nil {
					t.Fatal("deadline connection was reused")
				}
				t.Logf("deadline drained after %s; physical connection discarded", time.Since(start).Round(time.Millisecond))
			})
		}
		t.Run(backend.Selector+"/rows", func(t *testing.T) {
			db, err := openDatabase(readonlyDriverConfig(backend), 1)
			if err != nil {
				t.Fatal(readonlySafeError(backend, err))
			}
			defer db.Close()
			conn, err := db.Conn(context.Background())
			if err != nil {
				t.Fatal(readonlySafeError(backend, err))
			}
			defer conn.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			rows, err := conn.QueryContext(ctx, "SELECT CAST(42 AS BIGINT) AS RESULT_VALUE FROM DUAL")
			if err != nil {
				t.Fatal(readonlySafeError(backend, err))
			}
			columns, err := rows.ColumnTypes()
			if err != nil || len(columns) != 1 || columns[0].DatabaseTypeName() != "BIGINT" {
				t.Fatal("column metadata was not preserved")
			}
			cancel()
			_ = rows.Close()
			if err := conn.PingContext(context.Background()); err == nil {
				t.Fatal("canceled rows left a reusable connection")
			}
		})
	}
}

// A row lock makes the real server write wait. Only this uniquely named table is
// written or dropped. Releasing the lock proves that a deadline/administrator
// close cannot claim cancellation: the server may still complete the write.
func TestDamengExecutionDeadlineAndAdminCloseLive(t *testing.T) {
	path := os.Getenv("CHEN_WRITE_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_WRITE_BACKENDS to permit disposable-table cancellation tests")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private write fixture")
	}
	for _, backend := range cfg.Backends {
		if backend.Protocol != "dameng" {
			continue
		}
		if pick := os.Getenv("CHEN_WRITE_SELECTOR"); pick != "" && pick != backend.Selector {
			continue
		}
		for _, mode := range []string{"deadline", "admin-close"} {
			t.Run(backend.Selector+"/"+mode, func(t *testing.T) {
				auditDir := filepath.Join(t.TempDir(), "audit")
				provider := singleIntegrationProvider(t, path, auditDir)
				timeout := 800 * time.Millisecond
				if mode == "admin-close" {
					timeout = 10 * time.Second
				}
				server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: timeout})
				hs := httptest.NewServer(server.Handler())
				defer hs.Close()
				defer server.Close()
				h := &integrationHTTP{t: t, url: hs.URL, client: &http.Client{Timeout: 15 * time.Second}}
				owner := singleIntegrationSession(h, backend.Selector, "dm-drain-"+integrationKey())
				console := h.console(owner, backend.Database)
				observer, err := openDatabase(readonlyDriverConfig(backend), 2)
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer observer.Close()
				table := editQualified(console.Schema, "CHEN_DM_"+strings.ToUpper(integrationKey()[:12]), "dameng")
				if _, err = observer.ExecContext(context.Background(), "CREATE TABLE "+table+" (ID INT PRIMARY KEY, VAL INT)"); err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer func() {
					if _, err := observer.ExecContext(context.Background(), "DROP TABLE "+table); err != nil {
						t.Error("owned table cleanup failed:", readonlySafeError(backend, err))
					}
				}()
				if _, err = observer.ExecContext(context.Background(), "INSERT INTO "+table+" VALUES (1,0)"); err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				blocker, err := observer.BeginTx(context.Background(), nil)
				if err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				defer blocker.Rollback()
				if _, err = blocker.ExecContext(context.Background(), "UPDATE "+table+" SET VAL=0 WHERE ID=1"); err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				view := h.submit(owner, console, "UPDATE "+table+" SET VAL=1 WHERE ID=1", integrationKey())
				server.mu.RLock()
				session := server.sessions[tokenKey(owner.Token)]
				server.mu.RUnlock()
				session.mu.Lock()
				execution := session.executions[view.ID]
				physical := session.consoles[console.ID]
				session.mu.Unlock()
				waitUntil := time.Now().Add(3 * time.Second)
				for execution.snapshot().Status != "running" && time.Now().Before(waitUntil) {
					time.Sleep(10 * time.Millisecond)
				}
				if execution.snapshot().Status != "running" {
					t.Fatal("write did not enter running state")
				}
				time.Sleep(200 * time.Millisecond)
				if mode == "admin-close" {
					server.closeSession(session, "administrator terminated session")
				}
				select {
				case <-execution.ctx.Done():
				case <-time.After(3 * time.Second):
					t.Fatal("execution context was not canceled")
				}
				if terminal(execution.snapshot().Status) {
					t.Fatal("server claimed a terminal outcome before the database operation drained")
				}
				if err := blocker.Rollback(); err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				waitUntil = time.Now().Add(5 * time.Second)
				for execution.snapshot().FinishedAt == nil && time.Now().Before(waitUntil) {
					time.Sleep(20 * time.Millisecond)
				}
				final := execution.snapshot()
				if final.Status != "unknown" || final.Error == nil || final.Error.Code != "execution_unknown" || final.AuditStatus != "recorded" {
					t.Fatalf("interrupted in-flight write must be audited as unknown: status=%s audit=%s error=%v", final.Status, final.AuditStatus, final.Error)
				}
				physical.mu.Lock()
				pingErr := physical.conn.PingContext(context.Background())
				physical.mu.Unlock()
				if pingErr == nil {
					t.Fatal("interrupted physical connection was not discarded")
				}
				var value int
				if err := observer.QueryRowContext(context.Background(), "SELECT VAL FROM "+table+" WHERE ID=1").Scan(&value); err != nil {
					t.Fatal(readonlySafeError(backend, err))
				}
				if value != 1 {
					t.Fatal("fixture did not prove the in-flight server write could still complete")
				}
				waitSingleAudit(t, auditDir, func(events []singleAuditEvent) bool {
					for _, event := range events {
						if event.ExecutionID == final.ID && strings.Contains(event.Output, "state=unknown") {
							return true
						}
					}
					return false
				})
				t.Log("actual server write completed after cancellation; API and durable audit retained unknown outcome, physical connection discarded")
			})
		}
	}
}
