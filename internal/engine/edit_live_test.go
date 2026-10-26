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

// Every write targets a uniquely named table created by this test. The explicit
// write fixture opt-in is intentionally distinct from read-only acceptance tests.
func TestGridEditsLive(t *testing.T) {
	path := os.Getenv("CHEN_WRITE_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_WRITE_BACKENDS to allow disposable-table grid write tests")
	}
	config, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private write fixture")
	}
	for _, b := range config.Backends {
		if pick := os.Getenv("CHEN_WRITE_SELECTOR"); pick != "" && pick != b.Selector {
			continue
		}
		if b.Protocol == "clickhouse" {
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			provider := singleIntegrationProvider(t, path, filepath.Join(t.TempDir(), "audit"))
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 45 * time.Second})
			hs := httptest.NewServer(server.Handler())
			defer hs.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: hs.URL, client: &http.Client{Timeout: 50 * time.Second}}
			owner := singleIntegrationSession(h, b.Selector, "grid-"+integrationKey())
			c := h.console(owner, b.Database)
			name := "CHEN_E_" + strings.ToUpper(integrationKey()[:12])
			q := func(s string) string { return readonlyMatrixQuote(b.Protocol, s) }
			table := q(name)
			id, title, note := q("ID"), q("TITLE"), q("NOTE")
			idType := "BIGINT"
			textType := "VARCHAR(120)"
			if b.Protocol == "sqlserver" {
				textType = "NVARCHAR(120)"
			}
			if b.Protocol == "oracle" || b.Protocol == "oceanbase-oracle" {
				idType = "NUMBER(19)"
			}
			h.run(owner, c, "CREATE TABLE "+table+" ("+id+" "+idType+" NOT NULL PRIMARY KEY, "+title+" "+textType+" NOT NULL, "+note+" "+textType+")")
			table = editQualified(c.Schema, name, b.Protocol)
			observer, err := openDatabase(readonlyDriverConfig(b), 1)
			if err != nil {
				t.Fatal("cannot open isolated table observer")
			}
			defer observer.Close()
			defer func() {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				defer cancel()
				if _, err := observer.ExecContext(ctx, "DROP TABLE "+table); err != nil {
					t.Error("owned grid table cleanup failed:", readonlySafeError(b, err))
				}
			}()
			h.run(owner, c, "INSERT INTO "+table+" VALUES (9007199254740993, 'original', NULL)")
			base := "/consoles/" + c.ID
			browse := func() (Result, editMetadataView) {
				var e ExecutionView
				h.request("POST", base+"/browse", owner.Token, map[string]any{"table": name, "request_id": integrationKey()}, 202, &e)
				e = h.wait(owner, e.ID, "succeeded")
				if len(e.ResultIDs) != 1 {
					t.Fatal("browse result missing")
				}
				var r Result
				h.request("GET", "/results/"+e.ResultIDs[0]+"/rows", owner.Token, nil, 200, &r)
				var m editMetadataView
				h.request("GET", base+"/results/"+r.ID+"/edit", owner.Token, nil, 200, &m)
				if !m.Supported {
					t.Fatalf("table editing unsupported: %s", m.Reason)
				}
				return r, m
			}
			preview := func(r Result, changes []editChange) string {
				var p struct {
					PlanID string `json:"plan_id"`
				}
				h.request("POST", base+"/results/"+r.ID+"/edits/preview", owner.Token, editPreviewRequest{Source: "grid", Changes: changes}, 200, &p)
				return p.PlanID
			}
			apply := func(plan, status string) ExecutionView {
				var e ExecutionView
				key := integrationKey()
				path := base + "/edits/" + plan + "/apply"
				h.request("POST", path, owner.Token, map[string]any{"request_id": key}, 202, &e)
				done := h.wait(owner, e.ID, status)
				var repeated ExecutionView
				h.request("POST", path, owner.Token, map[string]any{"request_id": key}, 202, &repeated)
				if repeated.ID != e.ID {
					t.Fatal("save retry repeated database work")
				}
				return done
			}
			r, m := browse()
			// Luna explicitly sends source=grid. Keep the original omitted-source
			// contract working, while rejecting unknown sources at the HTTP edge.
			legacyChanges := []editChange{{Op: "update", RowRef: m.RowRefs[0], Values: map[string]*string{"1": editString("preview only")}}}
			var legacyPlan struct {
				PlanID string `json:"plan_id"`
			}
			h.request("POST", base+"/results/"+r.ID+"/edits/preview", owner.Token, editPreviewRequest{Changes: legacyChanges}, 200, &legacyPlan)
			if legacyPlan.PlanID == "" {
				t.Fatal("omitted edit source lost compatibility")
			}
			h.request("POST", base+"/results/"+r.ID+"/edits/preview", owner.Token, editPreviewRequest{Source: "other", Changes: legacyChanges}, 400, nil)
			p := preview(r, []editChange{{Op: "update", RowRef: m.RowRefs[0], Values: map[string]*string{"1": editString("中文🙂 saved"), "2": editString("")}}})
			saved := apply(p, "succeeded")
			if saved.Edit == nil || !saved.Edit.DatabaseCommitted || saved.Edit.AffectedRows != 1 {
				t.Fatal("successful save outcome missing")
			}
			var got string
			if err := observer.QueryRow("SELECT " + title + " FROM " + table).Scan(&got); err != nil || got != "中文🙂 saved" {
				t.Fatal("grid update was not committed exactly")
			}
			// A concurrent external change causes the entire save to roll back.
			r, m = browse()
			p = preview(r, []editChange{{Op: "insert", Values: map[string]*string{"0": editString("2"), "1": editString("must rollback")}}, {Op: "update", RowRef: m.RowRefs[0], Values: map[string]*string{"1": editString("stale overwrite")}}})
			if _, err := observer.Exec("UPDATE " + table + " SET " + title + "='external' WHERE " + id + "=9007199254740993"); err != nil {
				t.Fatal("owned table concurrent update failed")
			}
			failed := apply(p, "failed")
			if failed.Error == nil || failed.Error.Code != "edit_conflict" || failed.Edit == nil || failed.Edit.Outcome != "rolled_back" {
				t.Fatalf("optimistic conflict outcome wrong: %+v", failed)
			}
			var count int
			if err := observer.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil || count != 1 {
				t.Fatal("failed batch left a partial insert")
			}
			// The original exact BIGINT is never passed back by the browser.
			r, m = browse()
			p = preview(r, []editChange{{Op: "delete", RowRef: m.RowRefs[0]}, {Op: "insert", Values: map[string]*string{"0": editString("3"), "1": editString("new"), "2": nil}}})
			apply(p, "succeeded")
			if err := observer.QueryRow("SELECT COUNT(*) FROM " + table + " WHERE " + id + "=3").Scan(&count); err != nil || count != 1 {
				t.Fatal("table delete/insert failed")
			}
			// Confirm a second-statement constraint failure rolls back an already
			// successful first update, independent of conflict-check ordering.
			r, m = browse()
			p = preview(r, []editChange{{Op: "update", RowRef: m.RowRefs[0], Values: map[string]*string{"1": editString("must revert")}}, {Op: "insert", Values: map[string]*string{"0": editString("3"), "1": editString("duplicate")}}})
			failed = apply(p, "failed")
			if failed.Edit == nil || failed.Edit.Outcome != "rolled_back" {
				t.Fatal("constraint batch did not roll back")
			}
			if err := observer.QueryRow("SELECT " + title + " FROM " + table).Scan(&got); err != nil || got != "new" {
				t.Fatal("successful prefix survived constraint failure")
			}
			// CSV uses the same immutable plan, with server-side upload policy.
			r, _ = browse()
			var csvPlan struct {
				PlanID string `json:"plan_id"`
			}
			h.request("POST", base+"/results/"+r.ID+"/edits/preview", owner.Token, editPreviewRequest{Source: "csv", Changes: []editChange{{Op: "insert", Values: map[string]*string{"0": editString("4"), "1": editString("CSV 中文,\"quoted\""), "2": nil}}}}, 200, &csvPlan)
			apply(csvPlan.PlanID, "succeeded")
			if err := observer.QueryRow("SELECT " + title + " FROM " + table + " WHERE " + id + "=4").Scan(&got); err != nil || got != "CSV 中文,\"quoted\"" {
				t.Fatal("CSV append altered typed content")
			}
			h.run(owner, c, "DELETE FROM "+table+" WHERE "+id+"=4")
			if b.Protocol == "postgresql" || b.Protocol == "mysql" || b.Protocol == "mariadb" || b.Protocol == "oceanbase-mysql" || b.Protocol == "sqlserver" {
				begin := "BEGIN"
				if b.Protocol == "sqlserver" {
					begin = "BEGIN TRANSACTION"
				}
				h.run(owner, c, begin)
				query := h.query(owner, c, "SELECT * FROM "+table)
				var qm editMetadataView
				h.request("GET", base+"/results/"+query.ID+"/edit", owner.Token, nil, 200, &qm)
				if !qm.Supported {
					t.Fatal("query update unavailable")
				}
				p = preview(query, []editChange{{Op: "update", RowRef: qm.RowRefs[0], Values: map[string]*string{"1": editString("uncommitted")}}})
				staged := apply(p, "succeeded")
				if staged.Edit.DatabaseCommitted || staged.Edit.Outcome != "staged" {
					t.Fatal("outer user transaction was committed")
				}
				if b.Protocol != "sqlserver" {
					if err := observer.QueryRow("SELECT " + title + " FROM " + table).Scan(&got); err != nil || got != "new" {
						t.Fatal("user transaction leaked to observer")
					}
				}
				h.run(owner, c, "ROLLBACK")
				if fmt.Sprint(h.query(owner, c, "SELECT "+title+" FROM "+table).Rows[0][0]) != "new" {
					t.Fatal("user rollback did not restore edited row")
				}
			}
			t.Log("grid preview/parameterized update/insert/delete, exact PK, atomic optimistic conflict, idempotency and supported user transaction passed")
		})
	}
}
