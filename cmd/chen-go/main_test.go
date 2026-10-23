package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/jumpserver/chen/internal/jumpserver"
)

func TestWispTransportRequiresExplicitRemoteTrust(t *testing.T) {
	for _, tc := range []struct {
		address   string
		plaintext bool
		allowed   bool
	}{
		{"127.0.0.1:9090", false, true}, {"[::1]:9090", false, true}, {"localhost:9090", false, true},
		{"wisp:9090", false, false}, {"192.0.2.1:9090", false, false}, {"wisp:9090", true, true},
		{"static://wisp:9090", true, false},
	} {
		t.Run(tc.address, func(t *testing.T) {
			_, err := wispCredentials(tc.address, "", "", tc.plaintext)
			if (err == nil) != tc.allowed {
				t.Fatalf("transport acceptance=%v expected=%v", err == nil, tc.allowed)
			}
		})
	}
}

func TestModeSelectionAndManagedFlags(t *testing.T) {
	for _, args := range [][]string{{"single"}, {"standalone"}, {"--single"}, {"single", "-b", "custom.yml"}} {
		o, err := parseOptions(args)
		if err != nil || !o.single || o.backends == "" {
			t.Fatalf("single mode rejected: %v %v", args, err)
		}
	}
	for _, args := range [][]string{{"--backends", "local.yml"}, {"--web-dir", "web"}, {"--allow-insecure-backend-file"}, {"single", "extra"}, {"single", "--query-timeout", "0s"}} {
		if _, err := parseOptions(args); err == nil {
			t.Fatalf("invalid mode flags accepted: %v", args)
		}
	}
}

func TestSingleProviderDoesNotInitializeWisp(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "backends.yml")
	config := "version: 1\nbackends:\n  - selector: local-mysql\n    protocol: mysql\n    host: 127.0.0.1\n    port: 3306\n    user: example\n"
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	p, stop, err := configuredProvider(options{single: true, backends: path, state: dir, wisp: "intentionally-invalid-address", ca: "/does-not-exist.pem"})
	if err != nil {
		t.Fatalf("single mode depended on Wisp configuration: %v", err)
	}
	defer stop()
	g, err := p.Open(context.Background(), "local-mysql", "127.0.0.1:1234")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "outbox")); !os.IsNotExist(err) {
		t.Fatal("single mode created a managed audit outbox")
	}
}

func TestShutdownFailuresRemainVisibleWithoutLeakingDiagnostics(t *testing.T) {
	for _, tc := range []struct {
		cause error
		code  string
	}{
		{nil, ""},
		{context.DeadlineExceeded, "session_cleanup_deadline_exceeded"},
		{errors.Join(jumpserver.ErrAuditDisk, errors.New("private-diagnostic")), "session_cleanup_audit_persistence_failed"},
		{errors.New("private-diagnostic"), "session_cleanup_failed"},
	} {
		err := sessionCleanupError(tc.cause)
		if tc.code == "" && err == nil {
			continue
		}
		if err == nil || err.Error() != tc.code {
			t.Fatalf("unexpected shutdown result: %v", err)
		}
	}
}

func TestSingleStaticEntryAndLoopbackHostBoundary(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte("<html>workspace</html>"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "_nuxt"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "_nuxt", "editor.js"), []byte("// local editor"), 0600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(out, []byte("outside-secret"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(out, filepath.Join(dir, "escape")); err != nil {
		t.Fatal(err)
	}
	api := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("api")) })
	h, closeWeb, err := webHandler(api, options{single: true, listen: "127.0.0.1:8083", webDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	defer closeWeb()
	for _, tc := range []struct {
		method, path, host string
		status             int
		contains           string
	}{
		{"GET", "/?selector=my-db", "localhost:8083", 307, "/luna/chen/?selector=my-db"},
		{"GET", "/luna/chen/?selector=my-db", "127.0.0.1:8083", 200, "workspace"},
		{"GET", "/luna/_nuxt/editor.js", "[::1]:8083", 200, "local editor"},
		{"GET", "/luna/_nuxt/editor.js", "[::1]", 200, "local editor"},
		{"GET", "/luna/_nuxt/editor.js", "[::1", 403, ""},
		{"GET", "/luna/_nuxt/missing.js", "localhost:8083", 404, ""},
		{"GET", "/luna/_nuxt/", "localhost:8083", 404, ""},
		{"GET", "/luna/escape", "localhost:8083", 404, ""},
		{"GET", "/luna/../secret", "localhost:8083", 404, ""},
		{"POST", "/luna/chen/", "localhost:8083", 405, ""},
		{"GET", "/chen/api/v1/capabilities", "localhost:8083", 200, "api"},
		{"POST", "/chen/api/v1/sessions", "evil.example:8083", 403, ""},
	} {
		r := httptest.NewRequest(tc.method, tc.path, nil)
		r.Host = tc.host
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.contains) {
			t.Fatalf("%s %s: %d %s", tc.host, tc.path, w.Code, w.Body.String())
		}
	}
}
