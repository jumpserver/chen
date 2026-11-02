package engine

import (
	"context"
	"net/http"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

func prepareExplainSQL(dialect, document string) (string, error) {
	return sqlservice.EstimatedPlanSQL(sqlservice.Dialect(dialect), document, sqlservice.AnalyzeOptions{})
}

func (v *Server) prepareExplain(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	c := v.findConsole(w, r, s)
	if c == nil {
		return
	}
	var request struct {
		SQL string `json:"sql"`
	}
	if !decode(w, r, &request) {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	opts, err := liveOptions(r.Context(), c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 409, "sql_context_unavailable", "Console SQL context is unavailable")
		return
	}
	sql, _, err := estimatedPlanSQL(r.Context(), c, s.grant.Config.Dialect, request.SQL, opts)
	if err != nil {
		if !strings.HasPrefix(err.Error(), "explain_") {
			fail(w, 409, "sql_context_unavailable", "Console server version is unavailable")
			return
		}
		fail(w, 400, err.Error(), "Estimated plans require one read query and a supported database")
		return
	}
	writeJSON(w, 200, map[string]string{"sql": sql})
}

// Probe only plan-specific metadata on the locked physical console. Unknown
// versions retain the existing raw EXPLAIN; SQL is never retried in another format.
func estimatedPlanSQL(ctx context.Context, c *console, dialect, document string, opts sqlservice.AnalyzeOptions) (string, string, error) {
	command, err := sqlservice.EstimatedPlanSQL(sqlservice.Dialect(dialect), document, opts)
	if err != nil {
		return "", "", err
	}
	switch dialect {
	case "mariadb", "oceanbase-mysql", "clickhouse":
		var version string
		if err := c.conn.QueryRowContext(ctx, "SELECT VERSION()").Scan(&version); err != nil {
			return "", "", err
		}
		if dialect == "clickhouse" && supportedPlanVersion(dialect, version) {
			command = strings.Replace(command, "EXPLAIN PLAN ", sqlservice.ClickHouseJSONPlanPrefix, 1)
		}
		return command, version, nil
	default:
		return command, "", nil
	}
}
