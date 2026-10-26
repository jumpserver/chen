package engine

import (
	"errors"
	"net/http"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

func prepareExplainSQL(dialect, document string) (string, error) {
	switch dialect {
	case "mysql", "mariadb", "postgresql", "clickhouse", "oceanbase-mysql":
	default:
		return "", errors.New("explain_not_supported")
	}
	analysis, err := sqlservice.AnalyzeWithOptions(sqlservice.Dialect(dialect), document, sqlservice.AnalyzeOptions{})
	if err != nil || len(analysis.Statements) != 1 {
		return "", errors.New("explain_requires_single_select")
	}
	s := analysis.Statements[0]
	if s.MayWrite || !s.ReturnsRows || (s.Kind != "select" && s.Kind != "setopr") {
		return "", errors.New("explain_requires_single_select")
	}
	prefix := "EXPLAIN "
	if dialect == "postgresql" {
		prefix = "EXPLAIN (FORMAT JSON, ANALYZE FALSE) "
	}
	if dialect == "clickhouse" {
		prefix = "EXPLAIN PLAN "
	}
	return prefix + strings.TrimSpace(s.SQL), nil
}

func (v *Server) prepareExplain(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	if v.findConsole(w, r, s) == nil {
		return
	}
	var request struct {
		SQL string `json:"sql"`
	}
	if !decode(w, r, &request) {
		return
	}
	sql, err := prepareExplainSQL(s.grant.Config.Dialect, request.SQL)
	if err != nil {
		fail(w, 400, err.Error(), "Estimated plans require one read query and a supported database")
		return
	}
	writeJSON(w, 200, map[string]string{"sql": sql})
}
