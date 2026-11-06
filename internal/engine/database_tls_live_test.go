package engine

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

// Uses the real HTTP executor and databases. Only Core authorization/audit RPCs
// are replaced; this does not claim a managed Core/Wisp deployment test.
func TestLiveBackendTLSHTTP(t *testing.T) {
	for _, b := range readonlyBackends(t) {
		if !b.UseSSL || (b.Protocol != "mysql" && b.Protocol != "postgresql") {
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			cfg := readonlyDriverConfig(b)
			provider := &integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}
			server := New(provider, Options{StateDir: t.TempDir(), QueryTimeout: 10 * time.Second})
			httpServer := httptest.NewServer(server.Handler())
			defer httpServer.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 15 * time.Second}}
			owner := h.session("owner")
			console := h.console(owner, cfg.Database)
			query := "SHOW SESSION STATUS LIKE 'Ssl_cipher'"
			if b.Protocol == "postgresql" {
				query = "SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()"
			}
			result := h.query(owner, console, query)
			if len(result.Rows) != 1 {
				t.Fatal("missing backend TLS status")
			}
			status := fmt.Sprint(result.Rows[0])
			if (b.Protocol == "mysql" && !strings.Contains(status, "TLS_")) || (b.Protocol == "postgresql" && !strings.Contains(status, "true")) {
				t.Fatal("HTTP console did not use the configured TLS backend")
			}
			result = h.query(owner, console, "SELECT '中文' AS title, NULL AS absent, 'sensitive-original' AS secret")
			if len(result.Rows) != 1 || result.Rows[0][0] != "中文" || result.Rows[0][1] != nil || result.Rows[0][2] != "[masked]" {
				t.Fatal("TLS changed Unicode/NULL results or bypassed masking")
			}
			provider.mu.Lock()
			count := len(provider.records)
			provider.mu.Unlock()
			if count == 0 {
				t.Fatal("TLS queries did not reach the audit boundary")
			}
		})
		t.Run(b.Selector+"/bad-hostname", func(t *testing.T) {
			cfg := readonlyDriverConfig(b)
			cfg.TLS.ServerName = "wrong-identity.invalid"
			provider := &integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}
			server := New(provider, Options{StateDir: t.TempDir()})
			httpServer := httptest.NewServer(server.Handler())
			defer httpServer.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 15 * time.Second}}
			var response struct{ Error APIError }
			h.request("POST", "/sessions", "", map[string]any{"connect_token": "owner", "request_id": "tls-failure-" + integrationKey()}, 502, &response)
			if response.Error.Code != "database_tls_error" || strings.Contains(response.Error.Message, cfg.Password) {
				t.Fatal("TLS failure was not a safe, actionable HTTP diagnostic")
			}
		})
	}
}
