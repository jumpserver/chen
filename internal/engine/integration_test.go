package engine

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

// Run explicitly with:
// CHEN_INTEGRATION=1 GOTOOLCHAIN=go1.26.3 go test -race -count=1 -v ./internal/engine -run TestIntegrationDatabaseHTTP
// These tests create their own isolated disposable Docker containers and bind
// random localhost ports. They never use, stop or modify existing containers.
func TestIntegrationDatabaseHTTP(t *testing.T) {
	if os.Getenv("CHEN_INTEGRATION") != "1" {
		t.Skip("set CHEN_INTEGRATION=1 to run isolated MySQL/PostgreSQL Docker integration tests")
	}
	if _, err := exec.LookPath("docker"); err != nil {
		t.Fatal("Docker is required for explicit integration tests")
	}
	for _, dialect := range []string{"mysql", "postgresql"} {
		t.Run(dialect, func(t *testing.T) {
			cfg := integrationDatabase(t, dialect)
			provider := &integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}
			stateDir := t.TempDir()
			server := New(provider, Options{StateDir: stateDir, QueryTimeout: 30 * time.Second, MaxRows: 500, MaxConsoles: 8})
			httpServer := httptest.NewServer(server.Handler())
			t.Cleanup(func() { httpServer.Close(); server.Close() })
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 10 * time.Second}}
			owner := h.session("owner")
			again := h.session("owner")
			if again.Token != owner.Token || again.Session.ID != owner.Session.ID {
				t.Fatal("connection-token retry created another session")
			}
			if provider.openCount("owner") != 1 {
				t.Fatal("connection token was exchanged more than once")
			}
			h.request("POST", "/sessions", "", map[string]any{"connect_token": "owner", "request_id": "another-browser-nonce-12345"}, 409, nil)
			other := h.session("other")
			console := h.console(owner, cfg.Database)
			h.request("GET", "/sessions/"+owner.Session.ID, "", nil, 401, nil)
			h.request("GET", "/sessions/"+owner.Session.ID, other.Token, nil, 404, nil)
			h.request("POST", "/sessions", "", map[string]any{"connect_token": "invalid", "request_id": "test-invalid-session-nonce"}, 403, nil)
			h.request("POST", "/sessions", "", map[string]any{"connect_token": "owner", "password": "not-accepted-from-browser", "request_id": "test-unknown-fields-nonce"}, 400, nil)
			request, _ := http.NewRequest("GET", httpServer.URL+"/chen/api/v1/sessions/"+owner.Session.ID, nil)
			request.Header.Set("token", owner.Token)
			request.Header.Set("Origin", "https://untrusted.example")
			response, err := h.client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			response.Body.Close()
			if response.StatusCode != 403 {
				t.Fatal("untrusted origin accepted")
			}

			observer, err := openDatabase(cfg, 2)
			if err != nil {
				t.Fatal("observer connection configuration failed")
			}
			t.Cleanup(func() { observer.Close() })
			var idSQL string
			if dialect == "mysql" {
				idSQL = "SELECT CONNECTION_ID()"
			} else {
				idSQL = "SELECT pg_backend_pid()"
			}
			backendResult := h.query(owner, console, idSQL)
			backendID, err := strconv.ParseInt(fmt.Sprint(backendResult.Rows[0][0]), 10, 64)
			if err != nil {
				t.Fatal("backend ID is not lossless")
			}

			// Every value crosses the driver -> server JSON -> real HTTP decoder path.
			precisionSQL := "SELECT CAST(9007199254740993 AS SIGNED) AS big, CAST('12345678901234567890.1234567890' AS DECIMAL(40,10)) AS exact, NULL AS absent, '中文😀' AS title"
			if dialect == "postgresql" {
				precisionSQL = "SELECT 9007199254740993::bigint AS big, 12345678901234567890.1234567890::numeric(40,10) AS exact, NULL::text AS absent, '中文😀'::text AS title"
			}
			precision := h.query(owner, console, precisionSQL)
			if len(precision.Rows) != 1 || len(precision.Rows[0]) != 4 {
				t.Fatal("precision result shape changed")
			}
			want := []any{"9007199254740993", "12345678901234567890.1234567890", nil, "中文😀"}
			for i, value := range want {
				if precision.Rows[0][i] != value {
					t.Fatalf("typed value %d got %v (%T), expected %v", i, precision.Rows[0][i], precision.Rows[0][i], value)
				}
			}

			h.run(owner, console, "CREATE TABLE chen_items (id BIGINT PRIMARY KEY, name VARCHAR(100), secret VARCHAR(100))")
			insert := h.submit(owner, console, "INSERT INTO chen_items VALUES (1, 'alice', 'hidden')", "stable-write")
			h.wait(owner, insert.ID, "succeeded")
			retry := h.submit(owner, console, "INSERT INTO chen_items VALUES (1, 'alice', 'hidden')", "stable-write")
			if retry.ID != insert.ID {
				t.Fatal("idempotent retry created a second execution")
			}
			h.request("POST", "/consoles/"+console.ID+"/executions", owner.Token, map[string]any{"sql": "INSERT INTO chen_items VALUES (2, 'duplicate', 'hidden')", "request_id": "stable-write"}, 409, nil)
			count := h.query(owner, console, "SELECT COUNT(*) FROM chen_items")
			if fmt.Sprint(count.Rows[0][0]) != "1" {
				t.Fatal("write was repeated")
			}
			masked := h.query(owner, console, "SELECT secret FROM chen_items")
			if masked.Rows[0][0] != "[masked]" {
				t.Fatal("result masking was bypassed")
			}

			var relations struct {
				Items []sqlservice.Relation `json:"items"`
			}
			h.request("GET", "/consoles/"+console.ID+"/relations?prefix=chen_", owner.Token, nil, 200, &relations)
			found := false
			for _, r := range relations.Items {
				if r.Name == "chen_items" {
					found = true
				}
			}
			if !found {
				t.Fatal("catalog did not expose table")
			}
			var columns struct {
				Items []sqlservice.Column `json:"items"`
			}
			h.request("GET", "/consoles/"+console.ID+"/columns?table=chen_items", owner.Token, nil, 200, &columns)
			if len(columns.Items) != 3 {
				t.Fatalf("columns count %d", len(columns.Items))
			}
			for _, document := range []string{"SELECT i. FROM chen_items i", "WITH x AS (SELECT id AS selected_id FROM chen_items) SELECT x. FROM x", "SELECT q. FROM (SELECT id AS selected_id FROM chen_items) q"} {
				at := strings.Index(document, ". ") + 1
				if strings.HasPrefix(document, "WITH") {
					at = strings.LastIndex(document, ". ") + 1
				}
				var completion sqlservice.CompletionResponse
				h.request("POST", "/consoles/"+console.ID+"/completion", owner.Token, sqlservice.CompletionRequest{Document: document, UTF16Offset: len(utf16.Encode([]rune(document[:at]))), Version: 7}, 200, &completion)
				name := "id"
				if strings.Contains(document, "selected_id") {
					name = "selected_id"
				}
				found := false
				for _, i := range completion.Items {
					if i.Label == name {
						found = true
					}
				}
				if !found || completion.Version != 7 {
					t.Fatalf("live catalog completion missed %s", name)
				}
			}

			// Fixed physical connections preserve temporary tables and transactions.
			h.run(owner, console, "CREATE TEMPORARY TABLE chen_local (id INT)")
			h.run(owner, console, "INSERT INTO chen_local VALUES (11)")
			local := h.query(owner, console, "SELECT id FROM chen_local")
			if fmt.Sprint(local.Rows[0][0]) != "11" {
				t.Fatal("temporary table state was lost")
			}
			initialEvent := h.event(owner, 0)
			if initialEvent.Type != "reset" {
				t.Fatal("SSE subscription did not start with a snapshot")
			}
			// Closing the event HTTP stream must not close the database session.
			h.query(owner, console, "SELECT id FROM chen_local")
			replayed := h.event(owner, initialEvent.Sequence)
			if replayed.Sequence <= initialEvent.Sequence || replayed.Type != "execution.updated" {
				t.Fatal("SSE reconnect did not replay execution changes")
			}
			second := h.console(owner, cfg.Database)
			notVisible := h.submit(owner, second, "SELECT id FROM chen_local", integrationKey())
			h.wait(owner, notVisible.ID, "failed")
			h.run(owner, console, "BEGIN")
			h.run(owner, console, "INSERT INTO chen_items VALUES (2, 'rolled back', 'hidden')")
			h.run(owner, console, "ROLLBACK")
			count = h.query(owner, console, "SELECT COUNT(*) FROM chen_items")
			if fmt.Sprint(count.Rows[0][0]) != "1" {
				t.Fatal("transaction rollback did not affect the original connection")
			}
			if dialect == "mysql" {
				h.run(owner, console, "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'")
				modeQuery := h.query(owner, console, `SELECT "id" FROM "chen_items"`)
				if fmt.Sprint(modeQuery.Rows[0][0]) != "1" {
					t.Fatal("live SQL mode was ignored")
				}
				h.run(owner, console, "SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES'")
				modeQuery = h.query(owner, console, `SELECT 'a\' AS value`)
				if modeQuery.Rows[0][0] != "a\\" {
					t.Fatal("NO_BACKSLASH_ESCAPES changed literal data")
				}
			} else {
				h.run(owner, console, "SET search_path TO pg_catalog, public")
				if h.query(owner, console, "SELECT COUNT(*) FROM chen_items").Rows[0][0] != "1" {
					t.Fatal("search_path setting was not retained")
				}
			}
			if dialect == "mysql" {
				h.run(owner, console, "USE information_schema")
				h.request("GET", "/consoles/"+console.ID+"/relations?prefix=TABLES", owner.Token, nil, 200, &relations)
				if len(relations.Items) == 0 {
					t.Fatal("catalog did not follow live USE context")
				}
				h.run(owner, console, "USE chen_it")
			} else {
				h.run(owner, console, "CREATE SCHEMA chen_context_schema")
				h.run(owner, console, "SET search_path TO chen_context_schema, public")
				h.run(owner, console, "CREATE TABLE chen_context (id INT)")
				h.request("GET", "/consoles/"+console.ID+"/relations?prefix=chen_context", owner.Token, nil, 200, &relations)
				if len(relations.Items) != 1 || relations.Items[0].Name != "chen_context" {
					t.Fatal("catalog did not follow live search_path context")
				}
				h.request("GET", "/consoles/"+console.ID+"/columns?table=chen_context", owner.Token, nil, 200, &columns)
				if len(columns.Items) != 1 {
					t.Fatal("columns did not follow live search_path context")
				}
				h.request("GET", "/consoles/"+console.ID+"/columns?table=chen_items", owner.Token, nil, 200, &columns)
				if len(columns.Items) != 3 {
					t.Fatal("unqualified columns failed to search later schemas in search_path")
				}
				h.run(owner, console, "SET search_path TO public")
			}

			multiSQL := "SELECT 5 AS a; SELECT 6 AS b;"
			if dialect == "mysql" {
				h.run(owner, console, "CREATE PROCEDURE chen_two_results() BEGIN SELECT 5 AS a; SELECT 6 AS b; END")
				multiSQL = "CALL chen_two_results()"
			}
			multi := h.run(owner, console, multiSQL)
			if len(multi.ResultIDs) != 2 {
				t.Fatalf("multiple result sets lost: got %d", len(multi.ResultIDs))
			}
			for i, id := range multi.ResultIDs {
				var result Result
				h.request("GET", "/results/"+id+"/rows", owner.Token, nil, 200, &result)
				if len(result.Rows) != 1 || fmt.Sprint(result.Rows[0][0]) != strconv.Itoa(i+5) {
					t.Fatal("multiple result order/value changed")
				}
			}
			parts := []string{}
			for i := 1; i <= 10; i++ {
				parts = append(parts, fmt.Sprintf("SELECT %d AS n", i))
			}
			paged := h.run(owner, console, strings.Join(parts, " UNION ALL "))
			var page Result
			h.request("GET", "/results/"+paged.ResultIDs[0]+"/rows?offset=3&limit=3", owner.Token, nil, 200, &page)
			if page.TotalRows != 10 || len(page.Rows) != 3 || page.Offset != 3 || page.NextOffset == nil || *page.NextOffset != 6 || fmt.Sprint(page.Rows[0][0]) != "4" {
				t.Fatal("result paging lost boundaries or order")
			}

			// A valid script containing a forbidden suffix must send no prefix SQL.
			denied := h.submit(owner, console, "INSERT INTO chen_items VALUES (3,'should not run','hidden'); SELECT 'DENY_EXECUTION'", integrationKey())
			h.wait(owner, denied.ID, "failed")
			count = h.query(owner, console, "SELECT COUNT(*) FROM chen_items")
			if fmt.Sprint(count.Rows[0][0]) != "1" {
				t.Fatal("authorization was checked after a script prefix executed")
			}

			// A token cannot read/cancel/query another session's resources, and an
			// idempotent foreign DELETE must not close the owner's console.
			h.request("GET", "/executions/"+insert.ID, other.Token, nil, 404, nil)
			h.request("POST", "/executions/"+insert.ID+"/cancel", other.Token, nil, 404, nil)
			h.request("GET", "/results/"+precision.ID+"/rows", other.Token, nil, 404, nil)
			h.request("GET", "/consoles/"+console.ID+"/relations", other.Token, nil, 404, nil)
			h.request("POST", "/consoles/"+console.ID+"/executions", other.Token, map[string]any{"sql": "SELECT 1", "request_id": integrationKey()}, 404, nil)
			h.request("DELETE", "/consoles/"+console.ID, other.Token, nil, 204, nil)
			h.query(owner, console, "SELECT 1")

			// Observe a real long-running server statement before requesting cancel.
			// MySQL documents that a standalone SELECT SLEEP may return 1
			// successfully when interrupted. A UNION keeps SLEEP within a
			// compound statement so KILL QUERY reports error 1317 instead.
			sleepSQL := "SELECT /* chen_go_live_cancel */ SLEEP(15), 1 UNION ALL SELECT 0, 2"
			if dialect == "postgresql" {
				sleepSQL = "SELECT /* chen_go_live_cancel */ pg_sleep(15)"
			}
			long := h.submit(owner, console, sleepSQL, integrationKey())
			waitActiveStatement(t, observer, dialect, backendID, "chen_go_live_cancel", true)
			h.request("GET", "/consoles/"+console.ID+"/relations", owner.Token, nil, 409, nil)
			queued := h.submit(owner, console, "INSERT INTO chen_items VALUES (4,'queued cancel','hidden')", integrationKey())
			if queued.Status != "queued" {
				t.Fatalf("second execution was not queued: %s", queued.Status)
			}
			h.request("POST", "/executions/"+queued.ID+"/cancel", owner.Token, nil, 202, nil)
			cancelStarted := time.Now()
			h.request("POST", "/executions/"+long.ID+"/cancel", owner.Token, nil, 202, nil)
			h.wait(owner, long.ID, "canceled")
			waitActiveStatement(t, observer, dialect, backendID, "chen_go_live_cancel", false)
			if time.Since(cancelStarted) > 8*time.Second {
				t.Fatal("server cancellation exceeded eight seconds")
			}
			h.wait(owner, queued.ID, "canceled")
			count = h.query(owner, console, "SELECT COUNT(*) FROM chen_items")
			if fmt.Sprint(count.Rows[0][0]) != "1" {
				t.Fatal("canceled queued SQL was executed")
			}
			if h.query(owner, console, "SELECT id FROM chen_local").Rows[0][0] != "11" {
				t.Fatal("cancellation lost the console's temporary table")
			}

			if provider.auditCount(insert.ID) != 1 || provider.auditCount(long.ID) != 1 || provider.auditCount(queued.ID) != 1 || provider.auditCount(denied.ID) != 1 {
				t.Fatal("accepted execution was unaudited or audited twice")
			}
			for _, id := range []string{insert.ID, long.ID, queued.ID, denied.ID} {
				path := filepath.Join(stateDir, "executions", id+".json")
				data, err := os.ReadFile(path)
				if err != nil {
					t.Fatal("execution journal missing")
				}
				var journal struct {
					Phase     string        `json:"phase"`
					Execution ExecutionView `json:"execution"`
				}
				if err := json.Unmarshal(data, &journal); err != nil {
					t.Fatal(err)
				}
				if journal.Phase != "finished" || journal.Execution.FinishedAt == nil {
					t.Fatal("journal did not reach durable final state")
				}
				info, _ := os.Stat(path)
				if info.Mode().Perm() != 0600 {
					t.Fatal("execution journal is not private")
				}
			}
			// Automatic deadlines and resource closure must stop work on the
			// server as well. A driver context error alone proves no such thing.
			server.opts.QueryTimeout = 1200 * time.Millisecond
			timeoutSQL := strings.ReplaceAll(sleepSQL, "chen_go_live_cancel", "chen_go_timeout_cancel")
			timed := h.submit(owner, console, timeoutSQL, integrationKey())
			waitActiveStatement(t, observer, dialect, backendID, "chen_go_timeout_cancel", true)
			h.waitStatuses(owner, timed.ID, "canceled", "unknown")
			waitActiveStatement(t, observer, dialect, backendID, "chen_go_timeout_cancel", false)
			server.opts.QueryTimeout = 30 * time.Second
			closingConsole := h.console(owner, cfg.Database)
			closingIDResult := h.query(owner, closingConsole, idSQL)
			closingID, err := strconv.ParseInt(fmt.Sprint(closingIDResult.Rows[0][0]), 10, 64)
			if err != nil {
				t.Fatal("closing console backend ID invalid")
			}
			closeSQL := strings.ReplaceAll(sleepSQL, "chen_go_live_cancel", "chen_go_console_close")
			closing := h.submit(owner, closingConsole, closeSQL, integrationKey())
			waitActiveStatement(t, observer, dialect, closingID, "chen_go_console_close", true)
			h.request("DELETE", "/consoles/"+closingConsole.ID, owner.Token, nil, 204, nil)
			waitActiveStatement(t, observer, dialect, closingID, "chen_go_console_close", false)
			h.waitStatuses(owner, closing.ID, "canceled", "unknown")
			h.request("POST", "/consoles/"+closingConsole.ID+"/executions", owner.Token, map[string]any{"sql": "SELECT 1", "request_id": integrationKey()}, 404, nil)
			closingSessionConsole := h.console(owner, cfg.Database)
			closingIDResult = h.query(owner, closingSessionConsole, idSQL)
			closingID, err = strconv.ParseInt(fmt.Sprint(closingIDResult.Rows[0][0]), 10, 64)
			if err != nil {
				t.Fatal("closing session backend ID invalid")
			}
			closeSQL = strings.ReplaceAll(sleepSQL, "chen_go_live_cancel", "chen_go_session_close")
			closing = h.submit(owner, closingSessionConsole, closeSQL, integrationKey())
			waitActiveStatement(t, observer, dialect, closingID, "chen_go_session_close", true)
			h.request("DELETE", "/sessions/"+owner.Session.ID, owner.Token, nil, 204, nil)
			waitActiveStatement(t, observer, dialect, closingID, "chen_go_session_close", false)
			auditDeadline := time.Now().Add(6 * time.Second)
			for provider.auditCount(closing.ID) == 0 && time.Now().Before(auditDeadline) {
				time.Sleep(20 * time.Millisecond)
			}
			if provider.auditCount(closing.ID) != 1 {
				t.Fatal("session-close execution was not audited exactly once")
			}
			h.request("GET", "/results/"+precision.ID+"/rows", owner.Token, nil, 401, nil)
			h.request("GET", "/sessions/"+owner.Session.ID, owner.Token, nil, 401, nil)
			t.Logf("verified real %s as non-superuser chen_app: HTTP authentication, fixed connection state, typed rows, catalogs/completion, idempotency, scoped resources, server-confirmed cancel and audit", dialect)
		})
	}
}

// TestBrowserFixture exposes the same real HTTP handler and isolated database
// to a browser smoke test. It runs only by explicit opt-in and removes
// its own database, state and fixture metadata when stopped or after five minutes
// by default (an explicit duration may extend the upper bound to ten minutes).
func TestBrowserFixture(t *testing.T) {
	if os.Getenv("CHEN_BROWSER_FIXTURE") != "1" {
		t.Skip("set CHEN_BROWSER_FIXTURE=1 to expose the disposable browser integration fixture")
	}
	dialect := os.Getenv("CHEN_BROWSER_DIALECT")
	if dialect == "" {
		dialect = "mysql"
	}
	if dialect != "mysql" && dialect != "postgresql" {
		t.Fatal("CHEN_BROWSER_DIALECT must be mysql or postgresql")
	}
	cfg := integrationDatabase(t, dialect)
	db, err := openDatabase(cfg, 1)
	if err != nil {
		t.Fatal("browser fixture database configuration failed")
	}
	defer db.Close()
	if _, err = db.Exec("CREATE TABLE chen_browser_items (id BIGINT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))"); err != nil {
		t.Fatal("browser fixture schema failed")
	}
	if _, err = db.Exec("INSERT INTO chen_browser_items VALUES (9007199254740993, '数据库工作台', NULL)"); err != nil {
		t.Fatal("browser fixture seed failed")
	}
	provider := &integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}
	origins := []string{"http://127.0.0.1:5173", "http://localhost:5173", "http://127.0.0.1:5174", "http://localhost:5174", "http://127.0.0.1:3000", "http://localhost:3000"}
	if origin := os.Getenv("CHEN_BROWSER_ORIGIN"); origin != "" {
		origins = append(origins, origin)
	}
	server := New(provider, Options{StateDir: t.TempDir(), AllowedOrigins: origins, QueryTimeout: time.Minute})
	defer server.Close()
	mux := http.NewServeMux()
	mux.Handle("/chen/api/", server.Handler())
	mux.Handle("/healthy", server.Handler())
	staticDir := os.Getenv("CHEN_BROWSER_STATIC_DIR")
	if staticDir != "" {
		if _, err := os.Stat(filepath.Join(staticDir, "index.html")); err != nil {
			t.Fatal("CHEN_BROWSER_STATIC_DIR does not contain index.html")
		}
		static := http.StripPrefix("/luna/", http.FileServer(http.Dir(staticDir)))
		mux.HandleFunc("/luna/", func(w http.ResponseWriter, r *http.Request) {
			candidate := filepath.Join(staticDir, filepath.Clean(strings.TrimPrefix(r.URL.Path, "/luna/")))
			if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
				static.ServeHTTP(w, r)
				return
			}
			http.ServeFile(w, r, filepath.Join(staticDir, "index.html"))
		})
	}
	httpServer := httptest.NewServer(mux)
	defer httpServer.Close()
	fixturePath := os.Getenv("CHEN_BROWSER_FIXTURE_FILE")
	if fixturePath == "" {
		fixturePath = filepath.Join(t.TempDir(), "browser-fixture.json")
	}
	duration := 5 * time.Minute
	if configured := os.Getenv("CHEN_BROWSER_FIXTURE_DURATION"); configured != "" {
		value, err := time.ParseDuration(configured)
		if err != nil || value <= 0 || value > 10*time.Minute {
			t.Fatal("CHEN_BROWSER_FIXTURE_DURATION must be positive and at most 10m")
		}
		duration = value
	}
	expires := time.Now().Add(duration)
	fixture := map[string]any{"backend_url": httpServer.URL, "frontend_url": httpServer.URL + "/luna/", "connect_token": "owner", "database": cfg.Database, "protocol": cfg.Dialect, "dialect": cfg.Dialect, "session_protocol": cfg.Dialect, "stop_file": fixturePath + ".stop", "expires_at": expires.UTC().Format(time.RFC3339)}
	data, err := json.MarshalIndent(fixture, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	tmp, err := os.CreateTemp(filepath.Dir(fixturePath), ".chen-browser-fixture-")
	if err != nil {
		t.Fatal("create browser fixture metadata failed")
	}
	defer os.Remove(tmp.Name())
	if err = tmp.Chmod(0600); err == nil {
		_, err = tmp.Write(data)
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp.Name(), fixturePath)
	}
	if err != nil {
		t.Fatal("write browser fixture metadata failed")
	}
	t.Cleanup(func() { os.Remove(fixturePath); os.Remove(fixturePath + ".stop") })
	t.Logf("browser fixture ready: %s (expires in %s)", fixturePath, duration)
	for time.Now().Before(expires) {
		if _, err := os.Stat(fixturePath + ".stop"); err == nil {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func integrationDatabase(t *testing.T, dialect string) jumpserver.ConnectionConfig {
	t.Helper()
	password := integrationKey()
	name := "chen-go-it-" + dialect + "-" + integrationKey()[:12]
	image, port, passwordVar, user := "mysql:8.4", 3306, "MYSQL_ROOT_PASSWORD", "root"
	if dialect == "postgresql" {
		image, port, passwordVar, user = "postgres:16-alpine", 5432, "POSTGRES_PASSWORD", "postgres"
	}
	args := []string{"run", "--rm", "-d", "--name", name, "--label", "chen.go.integration=true", "-p", fmt.Sprintf("127.0.0.1::%d", port), "-e", passwordVar}
	env := append(os.Environ(), passwordVar+"="+password)
	if dialect == "mysql" {
		args = append(args, "-e", "MYSQL_DATABASE=chen_it")
	} else {
		args = append(args, "-e", "POSTGRES_DB=chen_it")
	}
	args = append(args, image)
	if dialect == "mysql" {
		args = append(args, "--innodb-buffer-pool-size=64M", "--performance-schema=OFF")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "docker", args...)
	cmd.Env = env
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("start isolated %s container: %v (%s)", dialect, err, strings.TrimSpace(string(output)))
	}
	t.Cleanup(func() {
		ctx, stop := context.WithTimeout(context.Background(), 20*time.Second)
		defer stop()
		if output, err := exec.CommandContext(ctx, "docker", "rm", "-f", "-v", name).CombinedOutput(); err != nil {
			t.Errorf("remove own integration container: %v (%s)", err, strings.TrimSpace(string(output)))
		}
	})
	output, err := exec.CommandContext(ctx, "docker", "port", name, fmt.Sprintf("%d/tcp", port)).Output()
	if err != nil {
		t.Fatal("find isolated container port failed")
	}
	_, portText, err := net.SplitHostPort(strings.TrimSpace(string(output)))
	if err != nil {
		t.Fatal("invalid isolated container port")
	}
	mapped, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal("invalid isolated container port")
	}
	cfg := jumpserver.ConnectionConfig{Dialect: dialect, Host: "127.0.0.1", Port: mapped, User: user, Password: password, Database: "chen_it"}
	db, err := openDatabase(cfg, 1)
	if err != nil {
		t.Fatal("test database configuration failed")
	}
	defer db.Close()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if dialect == "mysql" {
			// Docker's published port can accept and immediately close TCP
			// connections while MySQL initializes. Wait for the real server
			// greeting before asking the driver to authenticate, avoiding its
			// noisy expected-EOF diagnostics during normal container startup.
			probe, probeErr := net.DialTimeout("tcp", net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)), 200*time.Millisecond)
			if probeErr == nil {
				_ = probe.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
				var greeting [1]byte
				_, probeErr = probe.Read(greeting[:])
				_ = probe.Close()
			}
			if probeErr != nil {
				time.Sleep(200 * time.Millisecond)
				continue
			}
		}
		ctx, stop := context.WithTimeout(context.Background(), time.Second)
		err = db.PingContext(ctx)
		stop()
		if err == nil {
			appPassword := integrationKey()
			var setup []string
			if dialect == "mysql" {
				setup = []string{"CREATE USER 'chen_app'@'%' IDENTIFIED BY '" + appPassword + "'", "GRANT ALL PRIVILEGES ON chen_it.* TO 'chen_app'@'%'"}
			} else {
				setup = []string{"CREATE ROLE chen_app LOGIN PASSWORD '" + appPassword + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION", "GRANT CONNECT, CREATE, TEMPORARY ON DATABASE chen_it TO chen_app", "GRANT USAGE, CREATE ON SCHEMA public TO chen_app"}
			}
			for _, statement := range setup {
				if _, err := db.Exec(statement); err != nil {
					t.Fatal("create limited integration account failed")
				}
			}
			cfg.User = "chen_app"
			cfg.Password = appPassword
			return cfg
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("isolated %s database did not become ready within sixty seconds", dialect)
	return cfg
}

func waitActiveStatement(t *testing.T, db *sql.DB, dialect string, backendID int64, marker string, want bool) {
	t.Helper()
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		ctx, stop := context.WithTimeout(context.Background(), time.Second)
		var active int
		var err error
		if dialect == "mysql" {
			err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID = ? AND INFO LIKE ?", backendID, "%"+marker+"%").Scan(&active)
		} else {
			err = db.QueryRowContext(ctx, "SELECT COUNT(*) FROM pg_catalog.pg_stat_activity WHERE pid=$1 AND state='active' AND query LIKE $2", backendID, "%"+marker+"%").Scan(&active)
		}
		stop()
		if err != nil {
			t.Fatal("unable to observe test query on database server")
		}
		if (active > 0) == want {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("server statement active=%v did not become observable", want)
}

type integrationSession struct {
	Token   string      `json:"token"`
	Session SessionView `json:"session"`
}
type integrationHTTP struct {
	t        *testing.T
	url      string
	client   *http.Client
	sequence atomic.Uint64
}

func (h *integrationHTTP) request(method, path, token string, body any, status int, out any) {
	h.t.Helper()
	var encoded []byte
	var err error
	if body != nil {
		encoded, err = json.Marshal(body)
		if err != nil {
			h.t.Fatal(err)
		}
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		req, err := http.NewRequest(method, h.url+"/chen/api/v1"+path, bytes.NewReader(encoded))
		if err != nil {
			h.t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := h.client.Do(req)
		if err != nil {
			h.t.Fatal(err)
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
		resp.Body.Close()
		if err != nil {
			h.t.Fatal(err)
		}
		if resp.StatusCode != status {
			// A completed execution may still be flushing its final audit journal
			// under the console mutex. Only safe catalog/completion operations
			// retry this explicit transient response; SQL writes are never retried.
			if status == 200 && resp.StatusCode == 409 && (strings.Contains(path, "/relations") || strings.Contains(path, "/columns") || strings.Contains(path, "/completion")) && time.Now().Before(deadline) {
				var response struct {
					Error APIError `json:"error"`
				}
				_ = json.Unmarshal(data, &response)
				if response.Error.Code == "console_busy" {
					time.Sleep(20 * time.Millisecond)
					continue
				}
			}
			h.t.Fatalf("%s %s status=%d expected=%d body=%s", method, path, resp.StatusCode, status, data)
		}
		if out != nil {
			if err := json.Unmarshal(data, out); err != nil {
				h.t.Fatalf("decode %s response: %v", path, err)
			}
		}
		return
	}
}
func (h *integrationHTTP) session(connect string) integrationSession {
	h.t.Helper()
	var s integrationSession
	h.request("POST", "/sessions", "", map[string]any{"connect_token": connect, "request_id": "chen-integration-session-" + connect}, 201, &s)
	return s
}
func (h *integrationHTTP) console(s integrationSession, database string) ConsoleView {
	h.t.Helper()
	var c ConsoleView
	h.request("POST", "/sessions/"+s.Session.ID+"/consoles", s.Token, map[string]any{"database": database}, 201, &c)
	return c
}
func (h *integrationHTTP) submit(s integrationSession, c ConsoleView, query, key string) ExecutionView {
	h.t.Helper()
	var e ExecutionView
	h.request("POST", "/consoles/"+c.ID+"/executions", s.Token, map[string]any{"sql": query, "request_id": key}, 202, &e)
	return e
}
func (h *integrationHTTP) wait(s integrationSession, id, status string) ExecutionView {
	return h.waitStatuses(s, id, status)
}
func (h *integrationHTTP) waitStatuses(s integrationSession, id string, statuses ...string) ExecutionView {
	h.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	var e ExecutionView
	for time.Now().Before(deadline) {
		h.request("GET", "/executions/"+id, s.Token, nil, 200, &e)
		if terminal(e.Status) && e.FinishedAt != nil {
			allowed := false
			for _, status := range statuses {
				if e.Status == status {
					allowed = true
				}
			}
			if !allowed {
				h.t.Fatalf("execution ended %s expected one of %v error=%v", e.Status, statuses, e.Error)
			}
			if e.AuditStatus != "recorded" {
				h.t.Fatalf("audit status=%s", e.AuditStatus)
			}
			return e
		}
		time.Sleep(20 * time.Millisecond)
	}
	h.t.Fatalf("execution did not finish: status=%s", e.Status)
	return e
}
func (h *integrationHTTP) run(s integrationSession, c ConsoleView, query string) ExecutionView {
	h.t.Helper()
	return h.wait(s, h.submit(s, c, query, integrationKey()).ID, "succeeded")
}
func (h *integrationHTTP) query(s integrationSession, c ConsoleView, query string) Result {
	h.t.Helper()
	e := h.run(s, c, query)
	if len(e.ResultIDs) != 1 {
		h.t.Fatalf("query returned %d result sets", len(e.ResultIDs))
	}
	var result Result
	h.request("GET", "/results/"+e.ResultIDs[0]+"/rows", s.Token, nil, 200, &result)
	return result
}
func (h *integrationHTTP) event(s integrationSession, cursor uint64) Event {
	h.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", h.url+"/chen/api/v1/sessions/"+s.Session.ID+"/events", nil)
	if err != nil {
		h.t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+s.Token)
	if cursor != 0 {
		req.Header.Set("Last-Event-ID", strconv.FormatUint(cursor, 10))
	}
	resp, err := h.client.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		h.t.Fatal("SSE subscription failed")
	}
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 4096), 2<<20)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		var event Event
		if err := json.Unmarshal([]byte(strings.TrimPrefix(line, "data: ")), &event); err != nil {
			h.t.Fatal(err)
		}
		return event
	}
	h.t.Fatal("SSE did not deliver an event")
	return Event{}
}
func integrationKey() string {
	var value [20]byte
	if _, err := rand.Read(value[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(value[:])
}

// This fake replaces only JumpServer's remote authorization/audit boundary.
// Database SQL, network cancellation and HTTP handling remain real.
type integrationProvider struct {
	config  jumpserver.ConnectionConfig
	mu      sync.Mutex
	opens   map[string]int
	records map[string][]jumpserver.AuditRecord
}

func (p *integrationProvider) Open(ctx context.Context, token, remote string) (*jumpserver.Grant, error) {
	if token != "owner" && token != "other" && !strings.HasPrefix(token, "browser-") {
		return nil, errors.New("invalid test connect token")
	}
	p.mu.Lock()
	p.opens[token]++
	p.mu.Unlock()
	return &jumpserver.Grant{ID: integrationKey(), OwnerID: token, OwnerName: token, AssetID: "integration", AssetName: "isolated integration database", AccountName: "integration", Config: p.config, Permissions: jumpserver.Permissions{Connect: true, Copy: true, Paste: true}, ExpiresAt: time.Now().Add(time.Hour)}, nil
}
func (*integrationProvider) Connected(context.Context, *jumpserver.Grant) error { return nil }
func (*integrationProvider) Authorize(ctx context.Context, g *jumpserver.Grant, query string) error {
	if strings.Contains(query, "DENY_EXECUTION") {
		return &jumpserver.PolicyError{Code: "test_policy_denied", RiskLevel: 5}
	}
	return ctx.Err()
}
func (p *integrationProvider) Record(ctx context.Context, g *jumpserver.Grant, r jumpserver.AuditRecord) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.records[r.ID] = append(p.records[r.ID], r)
	return nil
}
func (*integrationProvider) Close(context.Context, *jumpserver.Grant) error { return nil }
func (*integrationProvider) Watch(ctx context.Context, g *jumpserver.Grant, callback func(jumpserver.Control)) {
	<-ctx.Done()
}
func (*integrationProvider) Mask(g *jumpserver.Grant, table, column string, value any) (any, bool) {
	if column == "secret" && value != nil {
		return "[masked]", true
	}
	return value, false
}
func (p *integrationProvider) openCount(token string) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.opens[token]
}
func (p *integrationProvider) auditCount(id string) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.records[id])
}
