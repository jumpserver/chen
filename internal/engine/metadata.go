package engine

import (
	"context"
	"errors"
	"net/http"
	"time"
	"unicode/utf16"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type editorStatement struct {
	From        int    `json:"from"`
	To          int    `json:"to"`
	Kind        string `json:"kind"`
	MayWrite    bool   `json:"may_write"`
	ReturnsRows bool   `json:"returns_rows"`
}

type editorAnalysis struct {
	Version    int               `json:"document_version"`
	Statements []editorStatement `json:"statements"`
}

// Use the same native parsers and live SQL mode as execution. Document text is
// only parsed, never executed or included in the coalesced metadata audit.
func (v *Server) analyzeDocument(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Document string `json:"document"`
		Version  int    `json:"document_version"`
	}
	if !decode(w, r, &req) {
		return
	}
	s, c, ctx, done := v.catalogConsole(w, r)
	if s == nil {
		return
	}
	defer done()
	if v.auditCatalog(ctx, s, c, "analysis") != nil {
		fail(w, 503, "audit_persistence_failed", "SQL analysis access could not be audited")
		return
	}
	options, err := liveOptions(ctx, c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to read database SQL settings")
		return
	}
	analysis, err := sqlservice.AnalyzeWithOptions(sqlservice.Dialect(s.grant.Config.Dialect), req.Document, options)
	if err != nil {
		fail(w, 400, "analysis_failed", "Cannot determine statement boundaries; select SQL explicitly or use Run all")
		return
	}
	if len(analysis.Statements) > 100 {
		fail(w, 400, "statement_limit", "A script must contain at most 100 statements")
		return
	}
	response := editorAnalysis{Version: req.Version, Statements: []editorStatement{}}
	for _, statement := range analysis.Statements {
		response.Statements = append(response.Statements, editorStatement{
			From:        len(utf16.Encode([]rune(req.Document[:statement.Start]))),
			To:          len(utf16.Encode([]rune(req.Document[:statement.End]))),
			Kind:        statement.Kind,
			MayWrite:    statement.MayWrite,
			ReturnsRows: statement.ReturnsRows,
		})
	}
	writeJSON(w, 200, response)
}

func (v *Server) catalogConsole(w http.ResponseWriter, r *http.Request) (*session, *console, context.Context, func()) {
	s := v.auth(w, r, false)
	if s == nil {
		return nil, nil, nil, nil
	}
	c := v.findConsole(w, r, s)
	if c == nil {
		return nil, nil, nil, nil
	}
	if !c.mu.TryLock() {
		fail(w, 409, "console_busy", "Metadata is unavailable while this console is executing")
		return nil, nil, nil, nil
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	stop := context.AfterFunc(c.ctx, cancel)
	s.grant.SetBusy(true)
	return s, c, ctx, func() { stop(); cancel(); s.grant.SetBusy(false); c.mu.Unlock() }
}

// Metadata access is audited as an internal operation, without copying partially
// typed SQL into audit on every keystroke. Each console and operation has a
// one-minute audit window; a persistence failure prevents new metadata access.
func (v *Server) auditCatalog(ctx context.Context, s *session, c *console, kind string) error {
	if c.catalogAuditAt == nil {
		c.catalogAuditAt = map[string]time.Time{}
	}
	now := time.Now()
	if now.Sub(c.catalogAuditAt[kind]) < time.Minute {
		return nil
	}
	err := v.provider.Record(ctx, s.grant, jumpserver.AuditRecord{ID: newID("meta_"), RiskClassified: true, SQL: "/* Chen metadata: " + kind + " */", Output: "Authorized catalog access; repeated requests summarized for one minute", StartedAt: now, FinishedAt: now})
	if err != nil {
		return errors.New("metadata audit could not be persisted")
	}
	c.catalogAuditAt[kind] = now
	return nil
}

// Read live context: USE and SET search_path from user SQL must also affect
// completion. The console's opening context is immutable for race-free snapshots.
func liveContext(ctx context.Context, c *console, dialect string) (sqlservice.Context, error) {
	return connectionContext(ctx, c.conn, dialect, c.context)
}
func (v *Server) relations(w http.ResponseWriter, r *http.Request) {
	s, c, ctx, done := v.catalogConsole(w, r)
	if s == nil {
		return
	}
	defer done()
	if v.auditCatalog(ctx, s, c, "relations") != nil {
		fail(w, 503, "audit_persistence_failed", "Metadata access could not be audited")
		return
	}
	current, err := liveContext(ctx, c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to read database context")
		return
	}
	items, err := sqlservice.Metadata(ctx, c.conn, sqlservice.Dialect(s.grant.Config.Dialect), sqlservice.MetadataRequest{Context: current, Prefix: r.URL.Query().Get("prefix"), Limit: 200})
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to load authorized database relations")
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}
func (v *Server) columns(w http.ResponseWriter, r *http.Request) {
	s, c, ctx, done := v.catalogConsole(w, r)
	if s == nil {
		return
	}
	defer done()
	if v.auditCatalog(ctx, s, c, "columns") != nil {
		fail(w, 503, "audit_persistence_failed", "Metadata access could not be audited")
		return
	}
	current, err := liveContext(ctx, c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to read database context")
		return
	}
	items, err := sqlservice.Columns(ctx, c.conn, sqlservice.Dialect(s.grant.Config.Dialect), sqlservice.ColumnRequest{Context: current, Schema: r.URL.Query().Get("schema"), Table: r.URL.Query().Get("table")})
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to load authorized database columns")
		return
	}
	writeJSON(w, 200, map[string]any{"items": items})
}
func (v *Server) completion(w http.ResponseWriter, r *http.Request) {
	var req sqlservice.CompletionRequest
	if !decode(w, r, &req) {
		return
	}
	s, c, ctx, done := v.catalogConsole(w, r)
	if s == nil {
		return
	}
	defer done()
	if v.auditCatalog(ctx, s, c, "completion") != nil {
		fail(w, 503, "audit_persistence_failed", "Completion access could not be audited")
		return
	}
	current, err := liveContext(ctx, c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to read database context")
		return
	}
	req.Context = current
	options, err := liveOptions(ctx, c, s.grant.Config.Dialect)
	if err != nil {
		fail(w, 502, "metadata_failed", "Unable to read database SQL settings")
		return
	}
	req.Options = options
	response, err := sqlservice.Completion(ctx, c.conn, sqlservice.Dialect(s.grant.Config.Dialect), req)
	if err != nil {
		fail(w, 400, "completion_failed", "Unable to analyze completion context")
		return
	}
	writeJSON(w, 200, response)
}
