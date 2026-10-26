package engine

import (
	"archive/zip"
	"context"
	"database/sql/driver"
	"encoding/csv"
	"encoding/json"
	"encoding/xml"
	"github.com/jumpserver/chen/internal/jumpserver"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type generatedExportRows struct {
	index, count int
	ctx          context.Context
	slow         bool
}

func (r *generatedExportRows) Columns() []string {
	return []string{"integer", "emoji", "precise", "formula", "null", "empty", "payload"}
}
func (r *generatedExportRows) Close() error                          { return nil }
func (r *generatedExportRows) ColumnTypeDatabaseTypeName(int) string { return "TEXT" }
func (r *generatedExportRows) Next(values []driver.Value) error {
	if r.ctx.Err() != nil {
		return r.ctx.Err()
	}
	if r.index >= r.count {
		return io.EOF
	}
	if r.slow {
		time.Sleep(time.Millisecond)
	}
	r.index++
	copy(values, []driver.Value{int64(r.index), "中文😀", "999999999999999999999999999999.12345678", "=HYPERLINK(\"https://invalid.example\")", nil, "", strings.Repeat("x", 1600)})
	return nil
}
func attachTestExport(t *testing.T, v *Server, s *session, e *execution, format string) *exportJob {
	t.Helper()
	s.grant.Permissions.Download = true
	s.mu.Lock()
	j, err := v.newExportLocked(s, format, "query", e)
	s.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	e.export = j
	e.view.ExportID = j.id
	return j
}
func TestFullExportStreamsBeyondOrdinaryResultWindow(t *testing.T) {
	for _, format := range []string{"csv", "xlsx"} {
		t.Run(format, func(t *testing.T) {
			const count = 12000
			state := &executionDriverState{query: func(ctx context.Context, _ string) (driver.Rows, error) {
				return &generatedExportRows{count: count, ctx: ctx}, nil
			}}
			v, s, c, e := executionFixture(t, "postgresql", "SELECT synthetic_readonly", state)
			j := attachTestExport(t, v, s, e, format)
			v.run(s, c, e)
			view := j.snapshot()
			if !view.Ready || view.Rows != count || view.Bytes <= 16<<20 || view.Truncated || e.snapshot().Status != "succeeded" {
				t.Fatalf("full export truncated or failed: %+v execution=%+v", view, e.snapshot())
			}
			if len(s.results) != 0 || s.resultBytes != 0 {
				t.Fatal("full export filled the interactive result cache")
			}
			j.mu.Lock()
			path := j.path
			j.mu.Unlock()
			stat, err := os.Stat(path)
			if err != nil || stat.Mode().Perm() != 0600 {
				t.Fatal("export artifact is not private")
			}
			if format == "csv" {
				f, _ := os.Open(path)
				defer f.Close()
				reader := csv.NewReader(f)
				n := 0
				for {
					row, err := reader.Read()
					if err == io.EOF {
						break
					}
					if err != nil {
						t.Fatal(err)
					}
					if n == 1 && (row[1] != "中文😀" || row[2] != "999999999999999999999999999999.12345678" || !strings.HasPrefix(row[3], "'=") || row[4] != `\N` || row[5] != "") {
						t.Fatal("CSV changed precision/emoji/null/formula behavior")
					}
					n++
				}
				if n != count+1 {
					t.Fatal("CSV row count changed")
				}
			} else {
				checkExportXLSX(t, path, count)
			}
			if destination := os.Getenv("CHEN_EXPORT_TEST_ARTIFACT_DIR"); destination != "" {
				in, _ := os.Open(path)
				defer in.Close()
				out, err := os.OpenFile(destination+"/chen-export-streaming."+format, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
				if err != nil {
					t.Fatal(err)
				}
				if _, err = io.Copy(out, in); err != nil {
					t.Fatal(err)
				}
				_ = out.Close()
			}
		})
	}
}
func checkExportXLSX(t *testing.T, path string, count int) {
	t.Helper()
	archive, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer archive.Close()
	found := false
	for _, part := range archive.File {
		if part.Name != "xl/worksheets/sheet1.xml" {
			continue
		}
		found = true
		reader, err := part.Open()
		if err != nil {
			t.Fatal(err)
		}
		defer reader.Close()
		decoder := xml.NewDecoder(reader)
		rows := 0
		for {
			token, err := decoder.Token()
			if err == io.EOF {
				break
			}
			if err != nil {
				t.Fatal(err)
			}
			start, ok := token.(xml.StartElement)
			if !ok {
				continue
			}
			if start.Name.Local == "f" {
				t.Fatal("export created an executable formula")
			}
			if start.Name.Local != "row" {
				continue
			}
			var row struct {
				Cells []struct {
					Ref  string `xml:"r,attr"`
					Type string `xml:"t,attr"`
					Text string `xml:"is>t"`
				} `xml:"c"`
			}
			if err = decoder.DecodeElement(&row, &start); err != nil {
				t.Fatal(err)
			}
			rows++
			if rows == 2 {
				cells := map[string]string{}
				for _, cell := range row.Cells {
					if cell.Type != "inlineStr" {
						t.Fatal("exact values must use text cells")
					}
					cells[cell.Ref] = cell.Text
				}
				if cells["B2"] != "中文😀" || cells["C2"] != "999999999999999999999999999999.12345678" || !strings.HasPrefix(cells["D2"], "=") {
					t.Fatal("XLSX changed precision/emoji/formula text")
				}
				if _, ok := cells["E2"]; ok {
					t.Fatal("NULL must be an absent XLSX cell")
				}
				if _, ok := cells["F2"]; !ok {
					t.Fatal("empty string must remain an explicit XLSX string cell")
				}
			}
		}
		if rows != count+1 {
			t.Fatalf("XLSX rows=%d expected=%d", rows, count+1)
		}
	}
	if !found {
		t.Fatal("XLSX worksheet missing")
	}
}
func TestExportLimitsNeverOfferPartialFiles(t *testing.T) {
	for _, which := range []string{"rows", "bytes", "cell"} {
		t.Run(which, func(t *testing.T) {
			v, s, _, e := executionFixture(t, "postgresql", "SELECT 1", &executionDriverState{})
			j := attachTestExport(t, v, s, e, "xlsx")
			if which == "rows" {
				j.limitRows = 1
			}
			if which == "bytes" {
				j.limitBytes = 64
			}
			n := 0
			err := j.produce(context.Background(), []Column{{Name: "text"}}, func() ([]any, bool, error) {
				n++
				if n > 2 {
					return nil, false, nil
				}
				text := "ok"
				if which == "cell" {
					text = strings.Repeat("😀", 16384)
				}
				return []any{text}, true, nil
			})
			if err == nil || j.snapshot().Ready {
				t.Fatal("limit silently truncated or offered an artifact")
			}
			j.mu.Lock()
			path := j.path
			j.mu.Unlock()
			if path != "" {
				t.Fatal("failed export retained its partial file")
			}
			if len(v.exportSlots) != 0 {
				t.Fatal("failed export retained capacity")
			}
		})
	}
}
func TestExportPolicyCannotBypassConsentReviewOrDenial(t *testing.T) {
	for _, sql := range []string{"SELECT 1 AS requires_warning", "SELECT 1 AS requires_review", "SELECT 1 AS rejected"} {
		var queries atomic.Int32
		v, s, c, e := executionFixture(t, "postgresql", sql, &executionDriverState{query: func(context.Context, string) (driver.Rows, error) { queries.Add(1); return &executionRows{}, nil }})
		v.provider = &warningExecutionProvider{}
		j := attachTestExport(t, v, s, e, "csv")
		v.run(s, c, e)
		if queries.Load() != 0 || j.snapshot().Ready || len(v.exportSlots) != 0 {
			t.Fatal("export bypassed policy or retained failed artifact quota")
		}
	}
}
func TestExportSingleQueryAndPermissionEnforcedBeforeDatabase(t *testing.T) {
	for _, sql := range []string{"DELETE FROM t", "SELECT 1; SELECT 2", "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d"} {
		var queries atomic.Int32
		v, s, c, e := executionFixture(t, "postgresql", sql, &executionDriverState{query: func(context.Context, string) (driver.Rows, error) { queries.Add(1); return &executionRows{}, nil }})
		attachTestExport(t, v, s, e, "csv")
		v.run(s, c, e)
		if queries.Load() != 0 || e.snapshot().Error.Code != "export_requires_single_select" {
			t.Fatal("non-read export was executed")
		}
	}
	v, s, c, _ := executionFixture(t, "postgresql", "", &executionDriverState{})
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/"+c.id+"/exports", `{"sql":"SELECT 1","format":"csv","request_id":"permission"}`, "token"))
	if w.Code != 403 || len(s.exports) != 0 {
		t.Fatal("export created without download permission")
	}
}
func TestExportNativeDownloadTicketOneUseAndRevocation(t *testing.T) {
	v, s, _, e := executionFixture(t, "postgresql", "", &executionDriverState{})
	j := attachTestExport(t, v, s, e, "csv")
	j.execution = nil
	j.mode = "cached"
	done := false
	if err := j.produce(context.Background(), []Column{{Name: "value"}}, func() ([]any, bool, error) {
		if done {
			return nil, false, nil
		}
		done = true
		return []any{"中文😀"}, true, nil
	}); err != nil {
		t.Fatal(err)
	}
	j.mu.Lock()
	j.approved = true
	j.status = "succeeded"
	j.mu.Unlock()
	ticket := func() string {
		w := httptest.NewRecorder()
		v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/exports/"+j.id+"/download-ticket", "{}", "token"))
		if w.Code != 201 {
			t.Fatal(w.Code)
		}
		var result struct {
			URL    string            `json:"download_url"`
			Fields map[string]string `json:"fields"`
		}
		_ = json.Unmarshal(w.Body.Bytes(), &result)
		if result.URL != "/chen/api/v1/downloads" || strings.Contains(result.URL, result.Fields["ticket"]) {
			t.Fatal("download credential leaked to URL")
		}
		return result.Fields["ticket"]
	}
	download := func(token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/chen/api/v1/downloads", strings.NewReader(url.Values{"ticket": {token}}.Encode()))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		w := httptest.NewRecorder()
		v.Handler().ServeHTTP(w, r)
		return w
	}
	token := ticket()
	if w := download(token); w.Code != 200 || !strings.Contains(w.Body.String(), "中文😀") || w.Header().Get("Content-Length") == "" {
		t.Fatal("native streamed download failed")
	}
	if w := download(token); w.Code != 410 {
		t.Fatal("one-use ticket replayed")
	}
	token = ticket()
	s.grant.Permissions.Download = false
	if w := download(token); w.Code != 403 {
		t.Fatal("ticket bypassed revoked download permission")
	}
}
func TestExportCancellationStopsWriterAndRemovesArtifact(t *testing.T) {
	v, s, c, e := executionFixture(t, "postgresql", "SELECT slow_synthetic", &executionDriverState{query: func(ctx context.Context, _ string) (driver.Rows, error) {
		return &generatedExportRows{count: 100000, ctx: ctx, slow: true}, nil
	}})
	j := attachTestExport(t, v, s, e, "csv")
	done := make(chan struct{})
	go func() { v.run(s, c, e); close(done) }()
	deadline := time.Now().Add(time.Second)
	for j.snapshot().Rows < 2 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/exports/"+j.id+"/cancel", "{}", "token"))
	if w.Code != 202 {
		t.Fatal(w.Code)
	}
	var canceled ExportView
	if err := json.Unmarshal(w.Body.Bytes(), &canceled); err != nil || canceled.ID != j.id || canceled.ExecutionID != e.view.ID || canceled.Format != "csv" || canceled.Mode != "query" {
		t.Fatalf("cancel response lost the export identity: %s", w.Body.String())
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not stop export")
	}
	if j.snapshot().Ready || len(v.exportSlots) != 0 {
		t.Fatal("canceled export offered a partial artifact")
	}
	w = httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/exports/"+j.id+"/cancel", "{}", "token"))
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &canceled) != nil || canceled.ID != j.id || canceled.ExecutionID != e.view.ID || canceled.Format != "csv" || canceled.Mode != "query" {
		t.Fatalf("terminal cancel response lost the export identity: %s", w.Body.String())
	}
}

type blockingExportAuditProvider struct {
	executionProvider
	started, release chan struct{}
}

func (p *blockingExportAuditProvider) Record(ctx context.Context, g *jumpserver.Grant, r jumpserver.AuditRecord) error {
	if strings.Contains(r.SQL, "cached result export") {
		close(p.started)
		<-p.release
	}
	return p.executionProvider.Record(ctx, g, r)
}
func TestCachedExportSessionCloseDuringAuditCannotResurrectReady(t *testing.T) {
	v, s, _, _ := executionFixture(t, "postgresql", "", &executionDriverState{})
	p := &blockingExportAuditProvider{started: make(chan struct{}), release: make(chan struct{})}
	v.provider = p
	s.grant.Permissions.Download = true
	s.results["cached"] = &Result{ID: "cached", Columns: []Column{{Name: "value"}}, Rows: [][]any{{"synthetic"}}}
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/results/cached/exports", `{"format":"csv","request_id":"close-during-audit"}`, "token"))
	if w.Code != 202 {
		t.Fatal(w.Code)
	}
	var job ExportView
	_ = json.Unmarshal(w.Body.Bytes(), &job)
	select {
	case <-p.started:
	case <-time.After(time.Second):
		t.Fatal("audit did not block")
	}
	s.mu.Lock()
	j := s.exports[job.ID]
	s.mu.Unlock()
	v.closeSession(s, "test-close")
	close(p.release)
	s.workers.Wait()
	if j.snapshot().Ready {
		t.Fatal("audit completion resurrected a closed export")
	}
	if j.snapshot().Status != "canceled" {
		t.Fatal("closed export was reported as successful")
	}
	j.mu.Lock()
	path := j.path
	j.mu.Unlock()
	if path != "" || len(v.exportSlots) != 0 {
		t.Fatal("closed export retained its artifact")
	}
}

func TestCachedExportCancelDuringAuditFinishesCanceled(t *testing.T) {
	v, s, _, _ := executionFixture(t, "postgresql", "", &executionDriverState{})
	p := &blockingExportAuditProvider{started: make(chan struct{}), release: make(chan struct{})}
	v.provider = p
	s.grant.Permissions.Download = true
	s.results["cached"] = &Result{ID: "cached", Columns: []Column{{Name: "value"}}, Rows: [][]any{{"synthetic"}}}
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/results/cached/exports", `{"format":"csv","request_id":"cancel-during-audit"}`, "token"))
	if w.Code != 202 {
		t.Fatal(w.Code)
	}
	var job ExportView
	_ = json.Unmarshal(w.Body.Bytes(), &job)
	select {
	case <-p.started:
	case <-time.After(time.Second):
		t.Fatal("audit did not block")
	}
	w = httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/exports/"+job.ID+"/cancel", "{}", "token"))
	close(p.release)
	if w.Code != 202 {
		t.Fatal(w.Code)
	}
	s.workers.Wait()
	s.mu.Lock()
	j := s.exports[job.ID]
	s.mu.Unlock()
	got := j.snapshot()
	if got.Ready || got.Status != "canceled" || got.Error == nil || got.Error.Code != "export_canceled" || len(v.exportSlots) != 0 {
		t.Fatalf("canceled audit completion was not terminal: %+v", got)
	}
}
