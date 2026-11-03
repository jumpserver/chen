package engine

import (
	"context"
	"encoding/csv"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type parityHTTPProvider struct{ integrationProvider }

func (p *parityHTTPProvider) Open(ctx context.Context, token, remote string) (*jumpserver.Grant, error) {
	g, err := p.integrationProvider.Open(ctx, token, remote)
	if g != nil {
		g.Permissions.Download = true
	}
	return g, err
}

func TestParityPostgresHTTP(t *testing.T) {
	if os.Getenv("CHEN_PARITY_INTEGRATION") != "1" {
		t.Skip("set CHEN_PARITY_INTEGRATION=1 for disposable database parity tests")
	}
	cfg := integrationDatabase(t, "postgresql")
	p := &parityHTTPProvider{integrationProvider{config: cfg, opens: map[string]int{}, records: map[string][]jumpserver.AuditRecord{}}}
	v := New(p, Options{StateDir: t.TempDir(), QueryTimeout: 30 * time.Second, MaxRows: 200})
	srv := httptest.NewServer(v.Handler())
	t.Cleanup(func() { srv.Close(); v.Close() })
	h := &integrationHTTP{t: t, url: srv.URL, client: &http.Client{Timeout: 15 * time.Second}}
	s := h.session("owner")
	c := h.console(s, cfg.Database)
	h.run(s, c, `CREATE TABLE public.parity_items (id integer PRIMARY KEY, name text UNIQUE, n integer CHECK(n>=0))`)
	h.run(s, c, `INSERT INTO public.parity_items SELECT i,'row-'||i,i FROM generate_series(1,10037) i`)
	h.run(s, c, `CREATE VIEW public.parity_view AS SELECT id,lower(name) AS name,n FROM public.parity_items`)
	var info sqlservice.TableInspection
	h.request("GET", "/sessions/"+s.Session.ID+"/catalog/inspect?schema=public&table=parity_items", s.Token, nil, 200, &info)
	constraints := info.Sections["constraints"]
	if !constraints.Supported || constraints.Error != "" || len(constraints.Items) != 3 {
		t.Fatalf("constraints: %+v", constraints)
	}
	for _, item := range constraints.Items {
		if item["definition"] == "" || item["definition"] == nil {
			t.Fatalf("constraint definition missing: %+v", item)
		}
	}
	r := h.query(s, c, `SELECT id,name,n FROM public.parity_view ORDER BY id`)
	if r.QueryPage == nil || !r.QueryPage.Supported || !r.QueryPage.RequiresReexecution {
		t.Fatalf("view query paging: %+v", r.QueryPage)
	}
	base := "/consoles/" + c.ID
	h.request("POST", base+"/query-page", s.Token, map[string]any{"source_result_id": r.ID, "action": "count", "request_id": integrationKey()}, 409, nil)
	var e ExecutionView
	h.request("POST", base+"/query-page", s.Token, map[string]any{"source_result_id": r.ID, "action": "count", "confirm_reexecution": true, "request_id": integrationKey()}, 202, &e)
	e = h.wait(s, e.ID, "succeeded")
	if e.QueryCount == nil || e.QueryCount.Total != "10037" {
		t.Fatalf("query count: %+v", e.QueryCount)
	}
	h.request("POST", base+"/query-page", s.Token, map[string]any{"source_result_id": r.ID, "offset": 10000, "page_size": 200, "confirm_reexecution": true, "request_id": integrationKey()}, 202, &e)
	e = h.wait(s, e.ID, "succeeded")
	var page Result
	h.request("GET", "/results/"+e.ResultIDs[0]+"/rows", s.Token, nil, 200, &page)
	if len(page.Rows) != 37 {
		t.Fatalf("full view page rows=%d", len(page.Rows))
	}
	export := func(endpoint string, source string, expected int) {
		t.Helper()
		req := map[string]any{"source_result_id": source, "action": "export", "format": "csv", "confirm_reexecution": true, "request_id": integrationKey()}
		h.request("POST", base+endpoint, s.Token, req, 202, &e)
		e = h.wait(s, e.ID, "succeeded")
		var job ExportView
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			h.request("GET", "/exports/"+e.ExportID, s.Token, nil, 200, &job)
			if job.Ready {
				break
			}
			time.Sleep(20 * time.Millisecond)
		}
		if !job.Ready || job.Rows != int64(expected) || job.Truncated {
			t.Fatalf("full confirmed export: %+v", job)
		}
		var ticket struct {
			URL    string            `json:"download_url"`
			Fields map[string]string `json:"fields"`
		}
		h.request("POST", "/exports/"+e.ExportID+"/download-ticket", s.Token, map[string]any{}, 201, &ticket)
		resp, err := h.client.PostForm(srv.URL+ticket.URL, url.Values{"ticket": {ticket.Fields["ticket"]}})
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		rows, err := csv.NewReader(resp.Body).ReadAll()
		if err != nil || resp.StatusCode != 200 || len(rows) != expected+1 {
			t.Fatalf("CSV rows=%d status=%d err=%v", len(rows), resp.StatusCode, err)
		}
	}
	export("/query-page", r.ID, 10037)
	for _, target := range []string{"parity_items", "parity_view"} {
		request := map[string]any{"schema": "public", "table": target, "where": `lower(name) LIKE 'row-%' AND id IN (SELECT id FROM public.parity_items WHERE n>=10000)`, "request_id": integrationKey()}
		h.request("POST", base+"/browse", s.Token, request, 409, nil)
		request["confirm_reexecution"] = true
		request["request_id"] = integrationKey()
		h.request("POST", base+"/browse", s.Token, request, 202, &e)
		e = h.wait(s, e.ID, "succeeded")
		h.request("GET", "/results/"+e.ResultIDs[0]+"/rows", s.Token, nil, 200, &page)
		if len(page.Rows) != 38 || page.Browse == nil || !page.Browse.RequiresReexecution {
			t.Fatalf("%s complex filter rows=%d browse=%+v", target, len(page.Rows), page.Browse)
		}
		h.request("POST", base+"/browse", s.Token, map[string]any{"source_result_id": page.ID, "action": "count", "confirm_reexecution": true, "request_id": integrationKey()}, 202, &e)
		e = h.wait(s, e.ID, "succeeded")
		if e.BrowseCount == nil || e.BrowseCount.Total != "38" {
			t.Fatalf("%s count=%+v", target, e.BrowseCount)
		}
		export("/browse", page.ID, 38)
	}
	h.run(s, c, `GRANT SELECT(id) ON public.parity_items TO parity_restricted`)
	db, err := openDatabase(cfg, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err = conn.ExecContext(context.Background(), `SET ROLE parity_restricted`); err != nil {
		t.Fatal(err)
	}
	limited, err := sqlservice.InspectTableSummary(context.Background(), conn, sqlservice.PostgreSQL, sqlservice.CatalogColumnRequest{Database: cfg.Database, Schema: "public", Table: "parity_items"})
	if err != nil || len(limited.Sections["constraints"].Items) != 1 || limited.Sections["constraints"].Items[0]["type"] != "PRIMARY KEY" {
		t.Fatalf("hidden-column constraints visible: %+v %v", limited.Sections["constraints"], err)
	}
}
