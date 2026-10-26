package engine

import (
	"encoding/csv"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/standalone"
)

// Synthetic constants and generate_series only: no application rows or writes.
func TestReadonlyPostgresExportJobs(t *testing.T) {
	path := os.Getenv("CHEN_READONLY_BACKENDS")
	if path == "" {
		t.Skip("private readonly backends configuration required")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("private configuration could not be loaded")
	}
	for _, backend := range cfg.Backends {
		if backend.Protocol != "postgresql" {
			continue
		}
		p := singleIntegrationProvider(t, path, filepath.Join(t.TempDir(), "audit"))
		v := New(p, Options{SingleMode: true, StateDir: t.TempDir(), MaxRows: 10000, MaxResultBytes: 16 << 20})
		server := httptest.NewServer(v.Handler())
		defer server.Close()
		defer v.Close()
		h := &integrationHTTP{t: t, url: server.URL, client: &http.Client{Timeout: 45 * time.Second}}
		owner := singleIntegrationSession(h, backend.Selector, "export-live-"+integrationKey())
		if !owner.Session.Permissions.Download {
			t.Fatal("single mode download permission missing")
		}
		console := h.console(owner, backend.Database)
		submit := func(format, sql string) ExecutionView {
			var e ExecutionView
			h.request("POST", "/consoles/"+console.ID+"/exports", owner.Token, map[string]any{"sql": sql, "format": format, "request_id": integrationKey()}, 202, &e)
			if e.ExportID == "" {
				t.Fatal("export has no stable id")
			}
			return e
		}
		wait := func(id string) ExportView {
			deadline := time.Now().Add(45 * time.Second)
			for time.Now().Before(deadline) {
				var j ExportView
				h.request("GET", "/exports/"+id, owner.Token, nil, 200, &j)
				if j.Ready {
					return j
				}
				if terminal(j.Status) && j.Status != "succeeded" {
					t.Fatalf("export failed: %s %v", j.Status, j.Error)
				}
				time.Sleep(25 * time.Millisecond)
			}
			t.Fatal("export did not finish")
			return ExportView{}
		}
		sql := `SELECT i::text AS integer,'中文😀' AS emoji,'999999999999999999999999999999.12345678' AS precise,'=HYPERLINK("https://invalid.example")' AS formula,NULL::text AS null_value,''::text AS empty,repeat('x',1600) AS payload FROM generate_series(1,12000) AS i`
		for _, format := range []string{"csv", "xlsx"} {
			e := submit(format, sql)
			job := wait(e.ExportID)
			if job.Rows != 12000 || job.Bytes <= 16<<20 || job.Truncated {
				t.Fatalf("full query reused bounded cache: %+v", job)
			}
			var ticket struct {
				URL    string            `json:"download_url"`
				Fields map[string]string `json:"fields"`
			}
			h.request("POST", "/exports/"+e.ExportID+"/download-ticket", owner.Token, map[string]any{}, 201, &ticket)
			resp, err := h.client.PostForm(server.URL+ticket.URL, url.Values{"ticket": {ticket.Fields["ticket"]}})
			if err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != 200 {
				resp.Body.Close()
				t.Fatalf("native form download: %d", resp.StatusCode)
			}
			output := filepath.Join(t.TempDir(), "export."+format)
			f, err := os.OpenFile(output, os.O_CREATE|os.O_WRONLY, 0600)
			if err != nil {
				t.Fatal(err)
			}
			size, err := io.Copy(f, resp.Body)
			resp.Body.Close()
			f.Close()
			if err != nil || size != resp.ContentLength {
				t.Fatal("native download was incomplete")
			}
			if format == "xlsx" {
				checkExportXLSX(t, output, 12000)
			} else {
				f, _ := os.Open(output)
				r := csv.NewReader(f)
				count := 0
				for {
					row, err := r.Read()
					if err == io.EOF {
						break
					}
					if err != nil {
						t.Fatal(err)
					}
					if count == 1 && (row[1] != "中文😀" || !strings.HasPrefix(row[3], "'=") || row[4] != `\N`) {
						t.Fatal("native CSV lost values")
					}
					count++
				}
				f.Close()
				if count != 12001 {
					t.Fatal("native CSV row count changed")
				}
			}
			h.request("DELETE", "/exports/"+e.ExportID, owner.Token, nil, 204, nil)
			t.Logf("real PostgreSQL %s: 12000 rows, %d raw bytes; native one-use POST download decoded", format, job.Bytes)
		}
		// Exercise actual production thresholds, not reduced test limits.
		for _, boundary := range []struct{ name, query, code string }{
			{"rows", `SELECT i::text FROM generate_series(1,1000001) AS i`, "export_row_limit"},
			{"bytes", `SELECT repeat('x',20000) FROM generate_series(1,10000) AS i`, "export_byte_limit"},
		} {
			start := time.Now()
			bounded := submit("csv", boundary.query)
			deadline := time.Now().Add(45 * time.Second)
			var job ExportView
			for time.Now().Before(deadline) {
				h.request("GET", "/exports/"+bounded.ExportID, owner.Token, nil, 200, &job)
				if terminal(job.Status) && job.Error != nil {
					break
				}
				time.Sleep(25 * time.Millisecond)
			}
			if job.Ready || job.Error == nil || job.Error.Code != boundary.code {
				t.Fatalf("default %s limit did not fail explicitly: %+v", boundary.name, job)
			}
			h.request("POST", "/exports/"+bounded.ExportID+"/download-ticket", owner.Token, map[string]any{}, 409, nil)
			recovered := h.submit(owner, console, "SELECT 1 AS limit_recovered", integrationKey())
			h.wait(owner, recovered.ID, "succeeded")
			t.Logf("real PostgreSQL default %s boundary: rows=%d bytes=%d elapsed=%s; no partial download and console recovered", boundary.name, job.Rows, job.Bytes, time.Since(start).Round(time.Millisecond))
		}
		slow := submit("csv", `SELECT i::text,repeat('x',1600),pg_sleep(0.01) FROM generate_series(1,100000) AS i`)
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			var e ExecutionView
			h.request("GET", "/executions/"+slow.ID, owner.Token, nil, 200, &e)
			if e.Status == "running" {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}
		h.request("POST", "/exports/"+slow.ExportID+"/cancel", owner.Token, nil, 202, nil)
		h.wait(owner, slow.ID, "canceled")
		var canceled ExportView
		h.request("GET", "/exports/"+slow.ExportID, owner.Token, nil, 200, &canceled)
		if canceled.Ready {
			t.Fatal("canceled export is downloadable")
		}
		h.request("POST", "/exports/"+slow.ExportID+"/download-ticket", owner.Token, map[string]any{}, 409, nil)
		recovery := h.submit(owner, console, "SELECT 1 AS connection_survives", integrationKey())
		h.wait(owner, recovery.ID, "succeeded")
		t.Log("real PostgreSQL export cancellation confirmed and original console usable")
		return
	}
	t.Fatal("PostgreSQL test backend not configured")
}
