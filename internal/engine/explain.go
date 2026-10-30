package engine

import (
	"net/http"

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
	sql, err := sqlservice.EstimatedPlanSQL(sqlservice.Dialect(s.grant.Config.Dialect), request.SQL, opts)
	if err != nil {
		fail(w, 400, err.Error(), "Estimated plans require one read query and a supported database")
		return
	}
	writeJSON(w, 200, map[string]string{"sql": sql})
}
