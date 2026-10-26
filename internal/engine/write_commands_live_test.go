package engine

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/standalone"
)

// Explicit write tests only create uniquely named, disposable CHEN_W_* tables.
// They never update existing tables. Their only DROP targets the exact object
// successfully created by this test, and cleanup also runs on assertion failure.
// CHEN_WRITE_BACKENDS=/private/backends.yml go test -tags ibmdb ./internal/engine -run TestWriteCommandsLive -v
func TestWriteCommandsLive(t *testing.T) {
	path := os.Getenv("CHEN_WRITE_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_WRITE_BACKENDS to explicitly allow disposable-table write tests")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private write fixture")
	}
	for _, b := range cfg.Backends {
		if pick := os.Getenv("CHEN_WRITE_SELECTOR"); pick != "" && pick != b.Selector {
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			provider := singleIntegrationProvider(t, path, filepath.Join(t.TempDir(), "audit"))
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 40 * time.Second})
			hs := httptest.NewServer(server.Handler())
			defer hs.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: hs.URL, client: &http.Client{Timeout: 45 * time.Second}}
			owner := singleIntegrationSession(h, b.Selector, "write-command-"+integrationKey())
			c := h.console(owner, b.Database)
			name := "CHEN_W_" + strings.ToUpper(integrationKey()[:12])
			q := func(s string) string { return readonlyMatrixQuote(b.Protocol, s) }
			table := q(name)
			if c.Schema != "" {
				table = q(c.Schema) + "." + table
			}
			id, title := q("ID"), q("TITLE")
			idType := "BIGINT"
			if b.Protocol == "oracle" || b.Protocol == "oceanbase-oracle" {
				idType = "NUMBER(19)"
			}
			create := "CREATE TABLE " + table + " (" + id + " " + idType + " NOT NULL PRIMARY KEY, " + title + " VARCHAR(120))"
			if b.Protocol == "clickhouse" {
				create = "CREATE TABLE " + table + " (" + id + " Int64, " + title + " String) ENGINE=MergeTree ORDER BY " + id
			}
			h.run(owner, c, create)
			// A separate connection can remove the owned table even if a deliberate
			// cancellation/error invalidates the HTTP console under test.
			t.Cleanup(func() {
				db, e := openDatabase(readonlyDriverConfig(b), 1)
				if e != nil {
					t.Error("cannot open test-object cleanup connection")
					return
				}
				defer db.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				defer cancel()
				if _, e = db.ExecContext(ctx, "DROP TABLE "+table); e != nil {
					t.Error("disposable test object cleanup failed:", readonlySafeError(b, e))
				}
			})
			h.run(owner, c, "INSERT INTO "+table+" ("+id+", "+title+") VALUES(9007199254740993, 'chen write')")
			update := "UPDATE " + table + " SET " + title + "='saved' WHERE " + id + "=9007199254740993"
			if b.Protocol == "clickhouse" {
				update = "ALTER TABLE " + table + " UPDATE " + title + "='saved' WHERE " + id + "=9007199254740993 SETTINGS mutations_sync=1"
			}
			h.run(owner, c, update)
			r := h.query(owner, c, "SELECT "+id+", "+title+" FROM "+table)
			if len(r.Rows) != 1 || fmt.Sprint(r.Rows[0][0]) != "9007199254740993" || r.Rows[0][1] != "saved" {
				t.Fatal("write round trip or precise primary key changed")
			}
			add := "ALTER TABLE " + table + " ADD " + q("NOTE") + " VARCHAR(80)"
			if b.Protocol == "clickhouse" {
				add = "ALTER TABLE " + table + " ADD COLUMN " + q("NOTE") + " String DEFAULT ''"
			}
			h.run(owner, c, add)
			if len(h.query(owner, c, "SELECT "+q("NOTE")+" FROM "+table).Rows) != 1 {
				t.Fatal("added column is unavailable")
			}
			del := "DELETE FROM " + table + " WHERE " + id + "=9007199254740993"
			if b.Protocol == "clickhouse" {
				del = "ALTER TABLE " + table + " DELETE WHERE " + id + "=9007199254740993 SETTINGS mutations_sync=1"
			}
			h.run(owner, c, del)
			if fmt.Sprint(h.query(owner, c, "SELECT COUNT(*) FROM "+table).Rows[0][0]) != "0" {
				t.Fatal("delete was not reflected")
			}
			t.Log("HTTP CREATE/INSERT/UPDATE/SELECT/ALTER/DELETE with exact bigint passed; disposable table cleanup registered")
		})
	}
}
