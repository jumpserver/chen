package engine

import (
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
	for _, dialect := range []string{"mysql", "mariadb", "postgresql", "clickhouse", "oceanbase-mysql"} {
		sql, err := prepareExplainSQL(dialect, "SELECT 1")
		if err != nil || !strings.HasPrefix(sql, "EXPLAIN ") {
			t.Fatalf("%s: %v", dialect, err)
		}
		for _, invalid := range []string{"SELECT 1; SELECT 2", "DELETE FROM t", "EXPLAIN ANALYZE SELECT 1", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT 1 INTO OUTFILE '/tmp/x'"} {
			if _, err := prepareExplainSQL(dialect, invalid); err == nil {
				t.Fatalf("unsafe explain input accepted by %s: %s", dialect, invalid)
			}
		}
	}
	for _, dialect := range []string{"oracle", "db2", "dameng", "sqlserver", "oceanbase-oracle"} {
		if _, err := prepareExplainSQL(dialect, "SELECT 1"); err == nil {
			t.Fatalf("unsupported plan accepted: %s", dialect)
		}
	}
}
