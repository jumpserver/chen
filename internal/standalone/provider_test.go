package standalone

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

func newTestProvider(t *testing.T) (*Provider, string) {
	t.Helper()
	cfg := testConfig()
	dir := filepath.Join(t.TempDir(), "audit")
	p, err := NewProvider(&cfg, dir)
	if err != nil {
		t.Fatal(err)
	}
	return p, dir
}
func auditEntries(t *testing.T, dir string) []AuditEntry {
	t.Helper()
	files, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	var entries []AuditEntry
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		var entry AuditEntry
		if json.Unmarshal(data, &entry) != nil {
			t.Fatal("audit is not valid JSON")
		}
		entries = append(entries, entry)
	}
	return entries
}

func TestProviderUsesExactSelectorAndFreshImmutableGrants(t *testing.T) {
	cfg := testConfig()
	p, err := NewProvider(&cfg, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cfg.Backends[0].Host = "changed.invalid"
	cfg.Backends[0].Password = "changed-password"
	for _, selector := range []string{"", "unknown", "LOCAL-MYSQL", " local-mysql", "mysql"} {
		if _, err := p.Open(context.Background(), selector, "192.0.2.1"); err == nil || err.Error() != "selector_not_found" {
			t.Fatalf("selector %q received fallback or unsafe error: %v", selector, err)
		}
	}
	g1, err := p.Open(context.Background(), "local-mysql", "192.0.2.1:56789")
	if err != nil {
		t.Fatal(err)
	}
	g2, err := p.Open(context.Background(), "local-mysql", "192.0.2.2")
	if err != nil {
		t.Fatal(err)
	}
	if g1.ID == g2.ID || g1.Config.Host != "127.0.0.1" || g1.Config.Password != "synthetic-db-password" {
		t.Fatal("provider reused a grant or observed mutable caller config")
	}
	if g1.MaxIdle != DefaultMaxIdle || g1.MaxDuration != DefaultMaxDuration || time.Until(g1.ExpiresAt) > DefaultMaxDuration || g1.Check() != nil || !g1.Permissions.Connect || !g1.Permissions.Copy || !g1.Permissions.Paste {
		t.Fatal("local lifetime and permissions were not initialized")
	}
	data, _ := json.Marshal(g1)
	for _, secret := range []string{"synthetic-db-password", "local_operator", "127.0.0.1"} {
		if strings.Contains(string(data), secret) {
			t.Fatal("browser grant exposed backend credentials")
		}
	}
	if err := p.Close(context.Background(), g1); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(g1.Check(), jumpserver.ErrClosed) || g2.Check() != nil {
		t.Fatal("closing one selector session closed a different session")
	}
}

func TestProviderDurablyRecordsConnectionAndQueryLifecycle(t *testing.T) {
	p, dir := newTestProvider(t)
	g, err := p.Open(context.Background(), "local-mysql", "192.0.2.1:4567")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Connected(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Connected(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Authorize(context.Background(), g, "SELECT 1"); err != nil {
		t.Fatal(err)
	}
	if err := p.Record(context.Background(), g, jumpserver.AuditRecord{ID: "exe_query", SQL: "SELECT 1", Output: "state=succeeded"}); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	entries := auditEntries(t, dir)
	want := []string{"connection_opened", "connection_connected", "query", "connection_closed"}
	if len(entries) != len(want) {
		t.Fatalf("lifecycle entry count=%d", len(entries))
	}
	for i, entry := range entries {
		if entry.Type != want[i] || entry.Version != 1 || entry.SessionID != g.ID || entry.Selector != "local-mysql" || entry.Protocol != "mysql" || entry.RemoteAddr != "192.0.2.1" {
			t.Fatalf("audit entry did not preserve identity: %+v", entry)
		}
	}
	if entries[2].ExecutionID != "exe_query" || entries[2].SQL != "SELECT 1" {
		t.Fatal("query audit lost its execution correlation")
	}
	if info, _ := os.Stat(dir); info.Mode().Perm() != 0700 {
		t.Fatal("audit directory is exposed")
	}
	files, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	for _, file := range files {
		info, _ := os.Stat(file)
		if info.Mode().Perm() != 0600 {
			t.Fatal("private audit file is exposed")
		}
	}
	p.mu.RLock()
	remaining := len(p.sessions)
	p.mu.RUnlock()
	if remaining != 0 {
		t.Fatal("closed grants leaked provider memory")
	}
}

func TestAuditRedactsBeforeBoundingDiagnosticAndPersistsCanceledContext(t *testing.T) {
	p, dir := newTestProvider(t)
	g, err := p.Open(context.Background(), "local-mysql", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	output := strings.Repeat("x", 2040) + "synthetic-db-password" + strings.Repeat("😀", 100)
	if err := p.Record(ctx, g, jumpserver.AuditRecord{ID: "exe_done", SQL: "SELECT 'synthetic-db-password'", Output: output, Error: "login diagnostic synthetic-db-password"}); err != nil {
		t.Fatal("completed SQL audit was dropped because its caller canceled")
	}
	for _, entry := range auditEntries(t, dir) {
		data, _ := json.Marshal(entry)
		if strings.Contains(string(data), "synthetic-db-password") {
			t.Fatal("audit leaked a configured password")
		}
		if len(entry.Output) > 2048 || len(entry.Error) > 2048 {
			t.Fatal("audit diagnostic exceeded its bound")
		}
	}
}

func TestAuditFailureIsSeparateAndCloseCanRetry(t *testing.T) {
	p, dir := newTestProvider(t)
	g, err := p.Open(context.Background(), "local-mysql", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if os.RemoveAll(dir) != nil {
		t.Fatal("remove audit directory failed")
	}
	if err := p.Record(context.Background(), g, jumpserver.AuditRecord{SQL: "INSERT INTO t VALUES(1)"}); !errors.Is(err, jumpserver.ErrAuditDisk) {
		t.Fatalf("audit disk failure vanished: %v", err)
	}
	if err := p.Close(context.Background(), g); !errors.Is(err, jumpserver.ErrAuditDisk) {
		t.Fatal("close audit failure vanished")
	}
	if !errors.Is(g.Check(), jumpserver.ErrClosed) {
		t.Fatal("audit outage left a closed local grant active")
	}
	if err := privateAuditDir(dir); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	entries := auditEntries(t, dir)
	if len(entries) != 1 || entries[0].Type != "connection_closed" {
		t.Fatal("close retry did not persist final lifecycle")
	}
}

func TestForeignProviderCannotUseOrCloseAnotherProvidersGrant(t *testing.T) {
	p, _ := newTestProvider(t)
	other, _ := newTestProvider(t)
	g, err := p.Open(context.Background(), "local-mysql", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := other.Authorize(context.Background(), g, "SELECT 1"); err == nil {
		t.Fatal("foreign provider authorized a grant")
	}
	_ = other.Close(context.Background(), g)
	if g.Check() != nil {
		t.Fatal("foreign provider revoked a grant it did not own")
	}
}

func TestConcurrentQueriesAndCloseHaveCompletePrivateAudit(t *testing.T) {
	p, dir := newTestProvider(t)
	g, err := p.Open(context.Background(), "local-mysql", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	var jobs sync.WaitGroup
	for i := 0; i < 16; i++ {
		jobs.Add(1)
		go func() {
			defer jobs.Done()
			if err := p.Record(context.Background(), g, jumpserver.AuditRecord{SQL: "SELECT 1", Output: "state=succeeded"}); err != nil {
				t.Error(err)
			}
		}()
	}
	jobs.Wait()
	for i := 0; i < 8; i++ {
		jobs.Add(1)
		go func() {
			defer jobs.Done()
			if err := p.Close(context.Background(), g); err != nil {
				t.Error(err)
			}
		}()
	}
	jobs.Wait()
	entries := auditEntries(t, dir)
	count := map[string]int{}
	for _, entry := range entries {
		count[entry.Type]++
	}
	if count["query"] != 16 || count["connection_opened"] != 1 || count["connection_closed"] != 1 {
		t.Fatalf("concurrent audit count=%v", count)
	}
}

func TestProviderRejectsSymlinkAuditDirectoryAndCanceledOpen(t *testing.T) {
	cfg := testConfig()
	target := t.TempDir()
	link := filepath.Join(t.TempDir(), "audit-link")
	if os.Symlink(target, link) != nil {
		t.Fatal("symlink creation failed")
	}
	if _, err := NewProvider(&cfg, link); err == nil {
		t.Fatal("symlink audit directory was accepted")
	}
	p, dir := newTestProvider(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := p.Open(ctx, "local-mysql", "192.0.2.1"); !errors.Is(err, context.Canceled) {
		t.Fatal("canceled open was accepted")
	}
	if len(auditEntries(t, dir)) != 0 {
		t.Fatal("never-opened canceled request claimed a connection lifecycle")
	}
}
