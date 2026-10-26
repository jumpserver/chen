package engine

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"unicode/utf16"
)

func TestEditorAnalysisUsesNativeSpansAndNeverExecutesDocument(t *testing.T) {
	for _, tc := range []struct{ dialect, document string }{
		{"postgresql", "SELECT '中文😀;';\nSELECT $$a;b$$;"},
		{"postgresql", "DO $$ BEGIN PERFORM 1; PERFORM 2; END $$; SELECT 3;"},
		{"mysql", "SELECT '中文😀;';\nSELECT `semi;colon` FROM `table`;"},
	} {
		t.Run(tc.dialect+tc.document, func(t *testing.T) {
			state := &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
				t.Fatal("analysis executed database SQL other than the fixed SQL-mode query")
				return nil, nil
			}}
			v, _, _, _ := executionFixture(t, tc.dialect, "", state)
			body, _ := json.Marshal(map[string]any{"document": tc.document, "document_version": 17})
			w := httptest.NewRecorder()
			v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/con_test/analysis", string(body), "token"))
			if w.Code != 200 {
				t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
			}
			var result editorAnalysis
			if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
				t.Fatal(err)
			}
			if result.Version != 17 || len(result.Statements) != 2 {
				t.Fatalf("unexpected spans: %+v", result)
			}
			document := utf16.Encode([]rune(tc.document))
			for _, span := range result.Statements {
				if span.From < 0 || span.To <= span.From || span.To > len(document) {
					t.Fatalf("invalid span: %+v", span)
				}
				if !strings.Contains(string(utf16.Decode(document[span.From:span.To])), "SELECT") && !strings.Contains(tc.document, "DO $$") {
					t.Fatalf("wrong UTF16 span: %+v", span)
				}
			}
			second := string(utf16.Decode(document[result.Statements[1].From:result.Statements[1].To]))
			if !strings.Contains(second, "SELECT") || strings.Contains(second, "😀") {
				t.Fatalf("second span selected first statement: %s", second)
			}
			if len(state.commands) != 0 {
				t.Fatal("analysis executed the document")
			}
			provider := v.provider.(*executionProvider)
			for _, record := range provider.records {
				if strings.Contains(record.SQL, tc.document) {
					t.Fatal("draft SQL leaked into metadata audit")
				}
			}
		})
	}
}

func TestEditorAnalysisRejectsUnauthorizedBusyAndInvalidInput(t *testing.T) {
	v, _, c, _ := executionFixture(t, "postgresql", "", &executionDriverState{})
	request := func(body, token string) int {
		w := httptest.NewRecorder()
		v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/con_test/analysis", body, token))
		return w.Code
	}
	valid := `{"document":"SELECT 1;","document_version":1}`
	if status := request(valid, ""); status != 401 {
		t.Fatalf("unauthenticated status=%d", status)
	}
	if status := request(`{"document":"SELECT '","document_version":2}`, "token"); status != 400 {
		t.Fatalf("invalid SQL status=%d", status)
	}
	c.mu.Lock()
	status := request(valid, "token")
	c.mu.Unlock()
	if status != 409 {
		t.Fatalf("busy console status=%d", status)
	}
}

func TestEditorAnalysisExposesWriteCapabilitiesWithoutExecuting(t *testing.T) {
	v, _, _, _ := executionFixture(t, "postgresql", "", &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		t.Fatal("write analysis executed SQL")
		return nil, nil
	}})
	body, _ := json.Marshal(map[string]any{"document": "SELECT 1; WITH changed AS (UPDATE items SET n=2 RETURNING *) SELECT * FROM changed; DELETE FROM items WHERE id=7;", "document_version": 4})
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/con_test/analysis", string(body), "token"))
	var a editorAnalysis
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &a) != nil || len(a.Statements) != 3 {
		t.Fatalf("analysis response: %d %s", w.Code, w.Body.String())
	}
	if a.Statements[0].MayWrite || !a.Statements[1].MayWrite || !a.Statements[1].ReturnsRows || !a.Statements[2].MayWrite || a.Statements[2].ReturnsRows {
		t.Fatalf("write capabilities: %+v", a)
	}
}
