package engine

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

// These opt-in tests use only disposable databases owned by integrationDatabase.
// They exercise the browser contract against both real drivers and standalone audit.
func TestIntegrationCatalogWorkspace(t *testing.T) {
	if os.Getenv("CHEN_INTEGRATION") != "1" {
		t.Skip("set CHEN_INTEGRATION=1 for disposable catalog integration")
	}
	for _, dialect := range []string{"mysql", "postgresql"} {
		t.Run(dialect, func(t *testing.T) {
			cfg := integrationDatabase(t, dialect)
			db, err := openDatabase(cfg, 1)
			if err != nil {
				t.Fatal("create fixture connection failed")
			}
			defer db.Close()
			quote := func(name string) string {
				q := "`"
				if dialect == "postgresql" {
					q = `"`
				}
				return q + strings.ReplaceAll(name, q, q+q) + q
			}
			for _, name := range []string{"chen_catalog_a", "chen_catalog_b", "chen_catalog_c", "chen%_catalog"} {
				if _, err := db.Exec("CREATE TABLE " + quote(name) + " (id INTEGER NOT NULL, note VARCHAR(40), enabled BOOLEAN NOT NULL DEFAULT TRUE)"); err != nil {
					t.Fatal("create catalog fixture table failed", err)
				}
			}
			if _, err := db.Exec("CREATE VIEW chen_catalog_view AS SELECT id FROM chen_catalog_a"); err != nil {
				t.Fatal(err)
			}
			wantTables := 3
			if dialect == "postgresql" {
				for _, statement := range []string{"CREATE SCHEMA chen_catalog_extra", "CREATE TABLE chen_catalog_extra.chen_catalog_z (id INTEGER)"} {
					if _, err := db.Exec(statement); err != nil {
						t.Fatal(err)
					}
				}
				wantTables++
			}
			configPath, auditDir := singleIntegrationConfig(t, cfg)
			provider := singleIntegrationProvider(t, configPath, auditDir)
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), MaxConsoles: 2, QueryTimeout: 30 * time.Second})
			httpServer := httptest.NewServer(server.Handler())
			t.Cleanup(func() { httpServer.Close(); server.Close() })
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 10 * time.Second}}
			owner := singleIntegrationSession(h, "primary", "catalog-workspace-owner-nonce")
			other := singleIntegrationSession(h, "primary", "catalog-workspace-other-nonce")
			base := "/sessions/" + owner.Session.ID + "/catalog"
			h.request("GET", base+"/schemas", "", nil, 401, nil)
			h.request("GET", base+"/schemas", other.Token, nil, 404, nil)
			var schemas struct {
				Database string `json:"database"`
				Items    []struct {
					Name string `json:"name"`
				} `json:"items"`
				Next string `json:"next_cursor"`
				More bool   `json:"has_more"`
			}
			schemaNames := map[string]bool{}
			cursor := ""
			for page := 0; ; page++ {
				if page >= 20 {
					t.Fatal("schema pagination did not terminate")
				}
				schemas.Next = ""
				h.request("GET", base+"/schemas?limit=1&cursor="+url.QueryEscape(cursor), owner.Token, nil, 200, &schemas)
				if schemas.Database != cfg.Database {
					t.Fatal("catalog database escaped configured scope")
				}
				for _, item := range schemas.Items {
					if schemaNames[item.Name] {
						t.Fatal("duplicate schema in pagination")
					}
					schemaNames[item.Name] = true
				}
				if !schemas.More {
					break
				}
				if schemas.Next == "" || schemas.Next == cursor {
					t.Fatal("missing progressing schema cursor")
				}
				cursor = schemas.Next
			}
			if dialect == "postgresql" && (!schemaNames["public"] || !schemaNames["chen_catalog_extra"] || schemaNames["pg_catalog"]) {
				t.Fatalf("incorrect PostgreSQL schemas: %v", schemaNames)
			}
			if dialect == "mysql" && (len(schemaNames) != 1 || !schemaNames[cfg.Database]) {
				t.Fatal("MySQL catalog did not stay within configured database")
			}
			type objectPage struct {
				Items []struct {
					Database string `json:"database"`
					Schema   string `json:"schema"`
					Name     string `json:"name"`
					Kind     string `json:"kind"`
				} `json:"items"`
				Next string `json:"next_cursor"`
				More bool   `json:"has_more"`
			}
			seen := map[string]bool{}
			cursor, firstCursor := "", ""
			for page := 0; ; page++ {
				if page >= 20 {
					t.Fatal("object pagination did not terminate")
				}
				var objects objectPage
				h.request("GET", base+"/objects?kind=table&q=chen_catalog&limit=1&cursor="+url.QueryEscape(cursor), owner.Token, nil, 200, &objects)
				if len(objects.Items) != 1 {
					t.Fatalf("unexpected page length: %d", len(objects.Items))
				}
				item := objects.Items[0]
				key := item.Schema + "." + item.Name
				if seen[key] || item.Kind != "table" || item.Database != cfg.Database {
					t.Fatal("catalog duplicated or escaped object scope")
				}
				seen[key] = true
				if !objects.More {
					break
				}
				if objects.Next == "" || objects.Next == cursor {
					t.Fatal("missing progressing object cursor")
				}
				cursor = objects.Next
				if firstCursor == "" {
					firstCursor = cursor
				}
			}
			if len(seen) != wantTables {
				t.Fatalf("server search omitted objects: got %d want %d", len(seen), wantTables)
			}
			// A page token cannot silently be reused for another filter or session.
			h.request("GET", base+"/objects?kind=view&q=chen_catalog&limit=1&cursor="+url.QueryEscape(firstCursor), owner.Token, nil, 400, nil)
			h.request("GET", "/sessions/"+other.Session.ID+"/catalog/objects?kind=table&q=chen_catalog&limit=1&cursor="+url.QueryEscape(firstCursor), other.Token, nil, 400, nil)
			var objects objectPage
			h.request("GET", base+"/objects?q="+url.QueryEscape("%_"), owner.Token, nil, 200, &objects)
			if len(objects.Items) != 1 || objects.Items[0].Name != "chen%_catalog" {
				t.Fatal("search treated literal wildcard characters as SQL wildcards")
			}
			h.request("GET", base+"/objects?kind=view&q=chen_catalog", owner.Token, nil, 200, &objects)
			if len(objects.Items) != 1 || objects.Items[0].Name != "chen_catalog_view" || objects.Items[0].Kind != "view" {
				t.Fatal("table/view classification failed")
			}
			schema := cfg.Database
			if dialect == "postgresql" {
				schema = "public"
			}
			cursor = ""
			var columnNames []string
			for page := 0; ; page++ {
				if page >= 10 {
					t.Fatal("column pagination did not terminate")
				}
				var columns struct {
					Items []struct {
						Name     string `json:"name"`
						DataType string `json:"data_type"`
						Nullable bool   `json:"nullable"`
					} `json:"items"`
					Next string `json:"next_cursor"`
					More bool   `json:"has_more"`
				}
				h.request("GET", base+"/columns?schema="+url.QueryEscape(schema)+"&table=chen_catalog_a&limit=1&cursor="+url.QueryEscape(cursor), owner.Token, nil, 200, &columns)
				if len(columns.Items) != 1 || columns.Items[0].DataType == "" {
					t.Fatal("missing column details")
				}
				column := columns.Items[0]
				if column.Nullable != (column.Name == "note") {
					t.Fatal("column nullability incorrect")
				}
				columnNames = append(columnNames, column.Name)
				if !columns.More {
					break
				}
				if columns.Next == "" || columns.Next == cursor {
					t.Fatal("missing progressing column cursor")
				}
				cursor = columns.Next
			}
			if strings.Join(columnNames, ",") != "id,note,enabled" {
				t.Fatalf("column ordinal order changed: %v", columnNames)
			}
			query := h.console(owner, cfg.Database)
			second := h.console(owner, cfg.Database)
			// MySQL's sole SLEEP() projection may return 1 successfully on KILL;
			// a compound query observes the server's interrupted-query outcome.
			idSQL, sleepSQL := "SELECT CONNECTION_ID()", "SELECT /* chen_catalog_running */ SLEEP(15), 1 UNION ALL SELECT 0, 2"
			if dialect == "postgresql" {
				idSQL, sleepSQL = "SELECT pg_backend_pid()", "SELECT /* chen_catalog_running */ pg_sleep(15)"
			}
			identity := h.query(owner, query, idSQL)
			backendID, err := strconv.ParseInt(fmt.Sprint(identity.Rows[0][0]), 10, 64)
			if err != nil {
				t.Fatal(err)
			}
			long := h.submit(owner, query, sleepSQL, integrationKey())
			waitActiveStatement(t, db, dialect, backendID, "chen_catalog_running", true)
			h.request("GET", base+"/objects?kind=table&q=chen_catalog", owner.Token, nil, 200, &objects)
			h.request("POST", "/executions/"+long.ID+"/cancel", owner.Token, nil, 202, nil)
			h.wait(owner, long.ID, "canceled")
			if h.query(owner, second, "SELECT 1").Rows[0][0] != "1" {
				t.Fatal("directory browsing interfered with another console")
			}
			h.request("DELETE", "/sessions/"+owner.Session.ID, owner.Token, nil, 204, nil)
			h.request("GET", base+"/schemas", owner.Token, nil, 401, nil)
			h.request("DELETE", "/sessions/"+other.Session.ID, other.Token, nil, 204, nil)
			t.Logf("%s: real schema/object/column paging, literal search, cursor isolation and catalog while running/canceling verified", dialect)
		})
	}
}
