package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
	"github.com/jumpserver/chen/internal/standalone"
)

// Explicitly opted-in real database tests. The provider is the actual standalone
// implementation with private local configuration and durable local audit; no
// Wisp client, Core mock or remote authorization service participates.
func TestIntegrationSingleMode(t *testing.T) {
	if os.Getenv("CHEN_INTEGRATION") != "1" {
		t.Skip("set CHEN_INTEGRATION=1 for disposable MySQL/PostgreSQL single-mode integration")
	}
	for _, dialect := range []string{"mysql", "postgresql"} {
		t.Run(dialect, func(t *testing.T) {
			cfg := integrationDatabase(t, dialect)
			configPath, auditDir := singleIntegrationConfig(t, cfg)
			if err := os.Chmod(configPath, 0644); err != nil {
				t.Fatal(err)
			}
			if _, err := standalone.Load(configPath, standalone.LoadOptions{}); err == nil {
				t.Fatal("readable credential config was accepted without explicit opt-in")
			}
			if err := os.Chmod(configPath, 0600); err != nil {
				t.Fatal(err)
			}
			provider := singleIntegrationProvider(t, configPath, auditDir)
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 30 * time.Second})
			httpServer := httptest.NewServer(server.Handler())
			t.Cleanup(func() { httpServer.Close(); server.Close() })
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 10 * time.Second}}
			var capabilities struct {
				Mode      string   `json:"mode"`
				Protocols []string `json:"protocols"`
			}
			h.request("GET", "/capabilities", "", nil, 200, &capabilities)
			if capabilities.Mode != "single" {
				t.Fatal("single mode was not discoverable through capabilities")
			}
			primaryNonce := "single-primary-session-nonce"
			first := singleIntegrationSession(h, "primary", primaryNonce)
			replay := singleIntegrationSession(h, "primary", primaryNonce)
			if first.Token != replay.Token || first.Session.ID != replay.Session.ID {
				t.Fatal("same selector and nonce was not idempotent")
			}
			second := singleIntegrationSession(h, "primary", "single-independent-tab-nonce")
			third := singleIntegrationSession(h, "alternate", primaryNonce)
			if first.Token == second.Token || first.Session.ID == second.Session.ID || first.Token == third.Token || first.Session.ID == third.Session.ID || second.Token == third.Token {
				t.Fatal("repeated selectors or request IDs reused an independent session")
			}
			if first.Session.Dialect != dialect || second.Session.Dialect != dialect {
				t.Fatal("selected database protocol was changed")
			}
			for _, body := range []map[string]any{
				{"connect_token": "primary", "request_id": "single-token-rejected-nonce"},
				{"selector": "primary", "connect_token": "ignored-token", "request_id": "single-mixed-rejected-nonce"},
				{"selector": "primary", "host": "127.0.0.1", "port": 1, "request_id": "single-arbitrary-host-nonce"},
				{"selector": "primary", "password": "from-browser", "request_id": "single-credentials-rejected"},
			} {
				h.request("POST", "/sessions", "", body, 400, nil)
			}
			h.request("POST", "/sessions", "", map[string]any{"selector": "not-configured", "request_id": "single-unknown-selector-nonce"}, 403, nil)
			// The managed protocol must also reject selector input rather than
			// treating it as a connection token or falling back to single mode.
			managed := New(&integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}, Options{StateDir: t.TempDir()})
			managedHTTP := httptest.NewServer(managed.Handler())
			mh := &integrationHTTP{t: t, url: managedHTTP.URL, client: &http.Client{Timeout: 5 * time.Second}}
			mh.request("POST", "/sessions", "", map[string]any{"selector": "primary", "request_id": "managed-selector-rejected-nonce"}, 400, nil)
			managedHTTP.Close()
			managed.Close()

			one := h.console(first, cfg.Database)
			two := h.console(second, cfg.Database)
			idSQL := "SELECT CONNECTION_ID()"
			if dialect == "postgresql" {
				idSQL = "SELECT pg_backend_pid()"
			}
			oneID := h.query(first, one, idSQL)
			twoID := h.query(second, two, idSQL)
			if fmt.Sprint(oneID.Rows[0][0]) == fmt.Sprint(twoID.Rows[0][0]) {
				t.Fatal("independent single sessions shared a physical database connection")
			}
			h.run(first, one, "CREATE TABLE chen_single_items(id BIGINT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))")
			h.run(first, one, "INSERT INTO chen_single_items VALUES (9007199254740993, '数据库工作台', NULL)")
			data := h.query(first, one, "SELECT id, name, note FROM chen_single_items")
			if len(data.Rows) != 1 || data.Rows[0][0] != "9007199254740993" || data.Rows[0][1] != "数据库工作台" || data.Rows[0][2] != nil {
				t.Fatal("single mode lost precise bigint, Unicode or NULL values")
			}
			completionSQL := "SELECT i. FROM chen_single_items i"
			var completion sqlservice.CompletionResponse
			position := strings.Index(completionSQL, ". ") + 1
			h.request("POST", "/consoles/"+one.ID+"/completion", first.Token, sqlservice.CompletionRequest{Document: completionSQL, UTF16Offset: len(utf16.Encode([]rune(completionSQL[:position]))), Version: 9}, 200, &completion)
			found := map[string]bool{}
			for _, item := range completion.Items {
				found[item.Label] = true
			}
			if completion.Version != 9 || !found["id"] || !found["name"] || !found["note"] {
				t.Fatal("single completion did not use the selected real database catalog")
			}
			h.run(first, one, "CREATE TEMPORARY TABLE chen_single_local(id INT)")
			h.run(first, one, "INSERT INTO chen_single_local VALUES(7)")
			invisible := h.submit(second, two, "SELECT id FROM chen_single_local", integrationKey())
			h.wait(second, invisible.ID, "failed")
			h.run(first, one, "BEGIN")
			h.run(first, one, "INSERT INTO chen_single_items VALUES(2, 'uncommitted', NULL)")
			if h.query(second, two, "SELECT COUNT(*) FROM chen_single_items").Rows[0][0] != "1" {
				t.Fatal("independent session observed an uncommitted transaction")
			}
			h.run(first, one, "ROLLBACK")
			h.request("GET", "/sessions/"+first.Session.ID, second.Token, nil, 404, nil)
			h.request("GET", "/results/"+data.ID+"/rows", second.Token, nil, 404, nil)
			h.request("POST", "/consoles/"+one.ID+"/executions", second.Token, map[string]any{"sql": "SELECT 1", "request_id": integrationKey()}, 404, nil)

			observer, err := openDatabase(cfg, 1)
			if err != nil {
				t.Fatal("single integration observer configuration failed")
			}
			defer observer.Close()
			backendID, err := strconv.ParseInt(fmt.Sprint(oneID.Rows[0][0]), 10, 64)
			if err != nil {
				t.Fatal("single integration backend ID invalid")
			}
			sleepSQL := "SELECT /* chen_single_cancel */ SLEEP(15), 1 UNION ALL SELECT 0, 2"
			if dialect == "postgresql" {
				sleepSQL = "SELECT /* chen_single_cancel */ pg_sleep(15)"
			}
			long := h.submit(first, one, sleepSQL, integrationKey())
			waitActiveStatement(t, observer, dialect, backendID, "chen_single_cancel", true)
			h.request("POST", "/executions/"+long.ID+"/cancel", first.Token, nil, 202, nil)
			h.wait(first, long.ID, "canceled")
			waitActiveStatement(t, observer, dialect, backendID, "chen_single_cancel", false)
			if h.query(first, one, "SELECT id FROM chen_single_local").Rows[0][0] != "7" {
				t.Fatal("single mode cancellation replaced the original connection")
			}
			h.request("DELETE", "/sessions/"+first.Session.ID, first.Token, nil, 204, nil)
			h.request("GET", "/results/"+data.ID+"/rows", first.Token, nil, 401, nil)
			// Ending one use of a selector cannot close any other independent use.
			if h.query(second, two, "SELECT COUNT(*) FROM chen_single_items").Rows[0][0] != "1" {
				t.Fatal("closing one selector session damaged another session")
			}
			h.request("DELETE", "/sessions/"+second.Session.ID, second.Token, nil, 204, nil)
			h.request("DELETE", "/sessions/"+third.Session.ID, third.Token, nil, 204, nil)
			events := waitSingleAudit(t, auditDir, func(events []singleAuditEvent) bool {
				closed := map[string]bool{}
				query := false
				for _, event := range events {
					if event.Type == "connection_closed" {
						closed[event.SessionID] = true
					}
					if event.Type == "query" && event.ExecutionID == long.ID {
						query = true
					}
				}
				return query && len(closed) == 3
			})
			opened, connected, closed := map[string]int{}, map[string]int{}, map[string]int{}
			queryIDs := map[string]int{}
			canceledAudited := false
			for _, event := range events {
				if event.Version != 1 || event.SessionID == "" || event.Selector == "" || event.Protocol != dialect {
					t.Fatal("local audit lost protocol or session identity")
				}
				switch event.Type {
				case "connection_opened":
					opened[event.SessionID]++
				case "connection_connected":
					connected[event.SessionID]++
				case "connection_closed":
					closed[event.SessionID]++
				case "query":
					if event.ExecutionID != "" {
						queryIDs[event.ExecutionID]++
					}
					if event.ExecutionID == long.ID && strings.Contains(event.SQL, "chen_single_cancel") && strings.Contains(event.Output, "state=canceled") && !event.StartedAt.IsZero() && !event.FinishedAt.IsZero() {
						canceledAudited = true
					}
				}
			}
			if len(opened) != 3 || len(connected) != 3 || len(closed) != 3 {
				t.Fatalf("local lifecycle count opened=%d connected=%d closed=%d", len(opened), len(connected), len(closed))
			}
			for id, count := range opened {
				if count != 1 || connected[id] != 1 || closed[id] != 1 {
					t.Fatal("local lifecycle was omitted or duplicated")
				}
			}
			if queryIDs[long.ID] != 1 || queryIDs[invisible.ID] != 1 || !canceledAudited {
				t.Fatal("local failed/canceled execution audit was not exactly once")
			}
			singleAuditNoSecrets(t, auditDir, cfg.Password)
			t.Logf("verified real %s standalone provider without Wisp: independent repeated-selector sessions, catalog completion, server cancel, private config and durable lifecycle/query audit", dialect)
		})
	}
}

func singleIntegrationConfig(t *testing.T, cfg jumpserver.ConnectionConfig) (string, string) {
	t.Helper()
	dir := t.TempDir()
	configPath := filepath.Join(dir, "single.yaml")
	auditDir := filepath.Join(dir, "audit")
	backend := func(selector string) map[string]any {
		return map[string]any{"selector": selector, "name": "Standalone " + selector, "protocol": cfg.Dialect, "host": cfg.Host, "port": cfg.Port, "user": cfg.User, "password": cfg.Password, "database": cfg.Database}
	}
	// JSON is a strict subset of YAML and avoids hand-escaping credentials.
	data, err := json.Marshal(map[string]any{"version": 1, "backends": []any{backend("primary"), backend("alternate")}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, data, 0600); err != nil {
		t.Fatal(err)
	}
	return configPath, auditDir
}
func singleIntegrationProvider(t *testing.T, path, auditDir string) *standalone.Provider {
	t.Helper()
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatalf("load private standalone configuration: %v", err)
	}
	provider, err := standalone.NewProvider(cfg, auditDir)
	if err != nil {
		t.Fatalf("create local standalone provider: %v", err)
	}
	return provider
}
func singleIntegrationSession(h *integrationHTTP, selector, nonce string) integrationSession {
	h.t.Helper()
	var session integrationSession
	h.request("POST", "/sessions", "", map[string]any{"selector": selector, "request_id": nonce}, 201, &session)
	return session
}

type singleAuditEvent struct {
	Version     int       `json:"version"`
	Type        string    `json:"type"`
	SessionID   string    `json:"session_id"`
	Selector    string    `json:"selector"`
	Protocol    string    `json:"protocol"`
	ExecutionID string    `json:"execution_id"`
	SQL         string    `json:"sql"`
	Output      string    `json:"output"`
	StartedAt   time.Time `json:"started_at"`
	FinishedAt  time.Time `json:"finished_at"`
}

func waitSingleAudit(t *testing.T, dir string, ready func([]singleAuditEvent) bool) []singleAuditEvent {
	t.Helper()
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		events := singleAuditEvents(t, dir)
		if ready(events) {
			return events
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("standalone local audit did not reach expected durable state")
	return nil
}
func singleAuditEvents(t *testing.T, dir string) []singleAuditEvent {
	t.Helper()
	info, err := os.Stat(dir)
	if err != nil {
		t.Fatal("local audit directory unavailable")
	}
	if info.Mode().Perm() != 0700 {
		t.Fatal("local audit directory is not private")
	}
	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal("read local audit directory failed")
	}
	events := []singleAuditEvent{}
	for _, file := range files {
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".json") {
			continue
		}
		info, err := file.Info()
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0600 {
			t.Fatal("local audit file is not private")
		}
		data, err := os.ReadFile(filepath.Join(dir, file.Name()))
		if err != nil {
			t.Fatal(err)
		}
		var event singleAuditEvent
		if err := json.Unmarshal(data, &event); err != nil {
			t.Fatal("local audit JSON invalid")
		}
		events = append(events, event)
	}
	return events
}
func singleAuditNoSecrets(t *testing.T, dir, password string) {
	t.Helper()
	err := filepath.WalkDir(dir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		if bytes.Contains(data, []byte(password)) || bytes.Contains(data, []byte("BEGIN PRIVATE KEY")) {
			t.Error("standalone local audit contains database credentials")
		}
		return nil
	})
	if err != nil {
		t.Fatal("inspect local audit files failed")
	}
}

// This fixture serves the real standalone provider to the normal /luna/chen/
// application. No managed connect token, injected page or Wisp service is used.
func TestSingleBrowserFixture(t *testing.T) {
	if os.Getenv("CHEN_SINGLE_BROWSER_FIXTURE") != "1" {
		t.Skip("set CHEN_SINGLE_BROWSER_FIXTURE=1 to expose the standalone browser fixture")
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
		t.Fatal("standalone browser database configuration failed")
	}
	defer db.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := db.ExecContext(ctx, "CREATE TABLE chen_browser_items(id BIGINT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))"); err != nil {
		t.Fatal("standalone browser seed table failed")
	}
	if _, err := db.ExecContext(ctx, "INSERT INTO chen_browser_items VALUES(9007199254740993,'数据库工作台',NULL)"); err != nil {
		t.Fatal("standalone browser seed data failed")
	}
	configPath, auditDir := singleIntegrationConfig(t, cfg)
	provider := singleIntegrationProvider(t, configPath, auditDir)
	origins := []string{"http://127.0.0.1:3019", "http://localhost:3019"}
	if value := os.Getenv("CHEN_BROWSER_ORIGIN"); value != "" {
		origins = append(origins, value)
	}
	server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), AllowedOrigins: origins, QueryTimeout: time.Minute})
	defer server.Close()
	mux := http.NewServeMux()
	mux.Handle("/chen/api/", server.Handler())
	mux.Handle("/healthy", server.Handler())
	if staticDir := os.Getenv("CHEN_BROWSER_STATIC_DIR"); staticDir != "" {
		if _, err := os.Stat(filepath.Join(staticDir, "index.html")); err != nil {
			t.Fatal("CHEN_BROWSER_STATIC_DIR has no index.html")
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
		fixturePath = filepath.Join(t.TempDir(), "single-browser-fixture.json")
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
	// The parent may launch the actual standalone CLI against this private
	// config and an independent private state directory for production-static
	// browser verification. It must stop that CLI before signaling stop_file.
	cliStateDir := t.TempDir()
	if err := os.Chmod(cliStateDir, 0700); err != nil {
		t.Fatal("make exported CLI state directory private failed")
	}
	metadata := map[string]any{"backend_url": httpServer.URL, "frontend_url": httpServer.URL + "/luna/chen/?selector=primary", "selector": "primary", "protocol": dialect, "dialect": dialect, "session_protocol": dialect, "database": cfg.Database, "seed_table": "chen_browser_items", "config_path": configPath, "state_dir": cliStateDir, "stop_file": fixturePath + ".stop", "expires_at": expires.UTC().Format(time.RFC3339)}
	data, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	tmp, err := os.CreateTemp(filepath.Dir(fixturePath), ".chen-single-browser-")
	if err != nil {
		t.Fatal("create standalone browser metadata failed")
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
		t.Fatal("write standalone browser metadata failed")
	}
	t.Cleanup(func() { os.Remove(fixturePath); os.Remove(fixturePath + ".stop") })
	t.Logf("standalone %s browser fixture ready: %s (expires in %s)", dialect, fixturePath, duration)
	for time.Now().Before(expires) {
		if _, err := os.Stat(fixturePath + ".stop"); err == nil {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
}
