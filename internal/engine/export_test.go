package engine

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLegacyResultExportRouteIsUnavailable(t *testing.T) {
	v, s, _, _ := executionFixture(t, "postgresql", "", &executionDriverState{})
	s.grant.Permissions.Download = true
	s.results["res_export"] = &Result{ID: "res_export", Columns: []Column{{Name: "value"}}, Rows: [][]any{{"cached-private-value"}}}
	for _, format := range []string{"csv", "json"} {
		for _, token := range []string{"", "token"} {
			w := httptest.NewRecorder()
			v.Handler().ServeHTTP(w, engineRequest("GET", "/chen/api/v1/results/res_export/export?format="+format, "", token))
			if w.Code != 404 || w.Header().Get("Content-Disposition") != "" || strings.Contains(w.Body.String(), "cached-private-value") {
				t.Fatalf("obsolete %s download remains available: status %d", format, w.Code)
			}
		}
	}
}

func TestExplainOnlyProducesEstimatedSingleReadSQL(t *testing.T) {
	for _, dialect := range []string{"mysql", "mariadb", "postgresql", "clickhouse", "oceanbase-mysql", "oracle", "db2", "dameng", "sqlserver", "oceanbase-oracle"} {
		t.Run(dialect, func(t *testing.T) {
			v, _, c, _ := executionFixture(t, dialect, "", &executionDriverState{})
			for _, document := range []string{"SELECT 1", "SELECT 1; SELECT 2", "DELETE FROM t", "EXPLAIN ANALYZE SELECT 1", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT 1 INTO OUTFILE '/tmp/x'"} {
				body, _ := json.Marshal(map[string]string{"sql": document})
				w := httptest.NewRecorder()
				v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/"+c.id+"/explain", string(body), "token"))
				allowed := document == "SELECT 1" && !specialPlanDialect(dialect)
				if (w.Code == 200) != allowed {
					t.Fatalf("explain acceptance: SQL=%s status=%d body=%s", document, w.Code, w.Body.String())
				}
				if allowed {
					var result struct {
						SQL string `json:"sql"`
					}
					if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil || !strings.HasPrefix(result.SQL, "EXPLAIN ") {
						t.Fatalf("invalid estimated plan response: %s", w.Body.String())
					}
				}
			}
		})
	}
}
