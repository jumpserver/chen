package engine

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

type QueryPage struct {
	RequiresReexecution bool   `json:"requires_reexecution_confirmation,omitempty"`
	Supported           bool   `json:"supported"`
	Reason              string `json:"reason,omitempty"`
	Mode                string `json:"mode"` // cache or database
	ScopeID             string `json:"scope_id,omitempty"`
	Ordered             bool   `json:"ordered"` // not a uniqueness or snapshot guarantee
	Offset              int    `json:"offset"`
	PageSize            int    `json:"page_size"`
	MaxPageSize         int    `json:"max_page_size"`
	MaxOffset           int    `json:"max_offset"`
	HasMore             *bool  `json:"has_more"`
	ReadOnlyReason      string `json:"read_only_reason,omitempty"`
}
type QueryCount struct {
	SourceResultID string `json:"source_result_id"`
	ScopeID        string `json:"scope_id"`
	Total          string `json:"total"`
}
type queryPageRequest struct {
	ConfirmReexecution bool   `json:"confirm_reexecution,omitempty"`
	SourceResultID     string `json:"source_result_id"`
	Action             string `json:"action"`
	Offset             int    `json:"offset"`
	PageSize           int    `json:"page_size"`
	Format             string `json:"format,omitempty"`
	RequestID          string `json:"request_id"`
	SubmitReview       bool   `json:"submit_review"`
	ConfirmWarnings    bool   `json:"confirm_warnings"`
}
type queryPagePlan struct {
	reexecutionConfirmed                bool
	view                                QueryPage
	query                               *sqlservice.QueryPageSQL
	original                            string
	options                             sqlservice.AnalyzeOptions
	context                             string
	columns                             int
	action, sourceResultID, fingerprint string
	generated                           string
}
type queryPageKey struct{}

func (v *Server) queryPage(w http.ResponseWriter, r *http.Request) {
	var req queryPageRequest
	if !decode(w, r, &req) {
		return
	}
	if req.RequestID == "" {
		req.RequestID = r.Header.Get("Idempotency-Key")
	}
	if req.RequestID == "" || len(req.RequestID) > 128 {
		fail(w, 400, "request_id_required", "A stable request_id is required")
		return
	}
	fingerprint, _ := json.Marshal(req)
	s := v.auth(w, r, true)
	if s == nil {
		return
	}
	s.mu.Lock()
	c := s.consoles[r.PathValue("cid")]
	var old *execution
	if c != nil {
		old = c.requests[req.RequestID]
	}
	source := s.results[req.SourceResultID]
	s.mu.Unlock()
	if old != nil {
		if old.queryPage == nil || old.queryPage.fingerprint != string(fingerprint) {
			fail(w, 409, "idempotency_conflict", "Request ID belongs to another operation")
			return
		}
		writeJSON(w, 202, old.snapshot())
		return
	}
	// Recover an accepted identity even after its connection is lost. A new
	// action still needs current permission and a ready original console.
	if v.auth(w, r, false) == nil {
		return
	}
	c = v.findConsole(w, r, s)
	if c == nil {
		return
	}
	if source == nil || source.consoleID != c.id || source.querySource == nil || !source.querySource.view.Supported {
		fail(w, 400, "query_page_unavailable", "Choose a supported result from this console; database paging cannot replace its SQL")
		return
	}
	if source.querySource.view.RequiresReexecution && !req.ConfirmReexecution {
		fail(w, 409, "query_reexecution_confirmation_required", "This operation runs the original query again; confirm its SQL before continuing")
		return
	}
	if req.Action == "" {
		req.Action = "page"
	}
	if req.Action != "page" && req.Action != "count" && req.Action != "export" {
		fail(w, 400, "invalid_query_page", "Unknown query paging action")
		return
	}
	if req.Offset < 0 || req.Offset > browseMaxOffset || req.PageSize < 0 || req.PageSize > min(1000, v.opts.MaxRows) {
		fail(w, 400, "invalid_query_page", "Page size or offset exceeds the limit")
		return
	}
	p := *source.querySource
	p.reexecutionConfirmed = req.ConfirmReexecution
	p.action, p.sourceResultID, p.fingerprint = req.Action, source.ID, string(fingerprint)
	p.view.Mode = "database"
	p.view.Offset = req.Offset
	if req.PageSize > 0 {
		p.view.PageSize = req.PageSize
	}
	if p.view.PageSize == 0 {
		p.view.PageSize = min(200, v.opts.MaxRows)
	}
	p.view.HasMore = nil
	p.view.ReadOnlyReason = "Database query pages are read-only because wrapped query provenance is not an editable table mapping"
	p.columns = len(source.Columns)
	switch p.action {
	case "page":
		names := make([]string, len(source.Columns))
		for i, column := range source.Columns {
			names[i] = column.Name
		}
		if err := p.query.ValidateColumns(names); err != nil {
			fail(w, 400, "query_page_unavailable", err.Error())
			return
		}
		if err := p.query.ValidatePageOrder(names); err != nil {
			fail(w, 400, "query_page_unavailable", err.Error())
			return
		}
		p.generated = p.query.Page(p.view.Offset, p.view.PageSize+1, names)
	case "count":
		names := make([]string, len(source.Columns))
		for i, col := range source.Columns {
			names[i] = col.Name
		}
		if err := p.query.ValidateColumns(names); err != nil {
			fail(w, 400, "query_page_unavailable", err.Error())
			return
		}
		p.generated = p.query.Count(p.columns)
	case "export":
		p.generated = p.original
	}
	body, _ := json.Marshal(map[string]any{"sql": p.original, "request_id": req.RequestID, "submit_review": req.SubmitReview, "confirm_warnings": req.ConfirmWarnings, "format": req.Format})
	next := context.WithValue(r.Context(), queryPageKey{}, &p)
	if p.action == "export" {
		next = context.WithValue(next, exportRequestContextKey{}, true)
	}
	r = r.Clone(next)
	r.Body = io.NopCloser(bytes.NewReader(body))
	v.submit(w, r)
}

// This snapshot is taken on the same physical connection, under the worker lock.
// An old result cannot be moved into a new console after reconnect.
func queryPageContext(ctx context.Context, c *console, d string) (string, error) {
	var values []string
	if d == "postgresql" {
		values = make([]string, 7)
		err := c.conn.QueryRowContext(ctx, "SELECT pg_catalog.current_database(), pg_catalog.current_schema(), pg_catalog.current_setting('search_path'), pg_catalog.current_setting('standard_conforming_strings'), current_user, pg_catalog.current_setting('TimeZone'), pg_catalog.current_setting('row_security')").Scan(&values[0], &values[1], &values[2], &values[3], &values[4], &values[5], &values[6])
		if err != nil {
			return "", err
		}
		if values[3] != "on" {
			return "", errors.New("Database paging requires standard_conforming_strings=on")
		}
	} else if d != "mysql" && d != "mariadb" && d != "oceanbase-mysql" {
		return vendorQueryPageContext(ctx, c, d)
	} else {
		values = make([]string, 6)
		err := c.conn.QueryRowContext(ctx, "SELECT COALESCE(DATABASE(), ''), CURRENT_USER(), @@session.sql_mode, @@session.time_zone, @@session.character_set_connection, @@session.collation_connection").Scan(&values[0], &values[1], &values[2], &values[3], &values[4], &values[5])
		if err != nil {
			return "", err
		}
	}
	b, _ := json.Marshal(values)
	return string(b), nil
}
func (v *Server) captureQuerySource(s *session, c *console, e *execution, opts sqlservice.AnalyzeOptions) {
	v.captureQuerySQLSource(s, c, e, opts, e.sql)
}
func (v *Server) captureQuerySQLSource(s *session, c *console, e *execution, opts sqlservice.AnalyzeOptions, statementSQL string) {
	p := &queryPagePlan{original: statementSQL, options: opts, view: QueryPage{Mode: "cache", MaxPageSize: min(1000, v.opts.MaxRows), MaxOffset: browseMaxOffset}}
	e.querySource = p
	q, err := sqlservice.AnalyzeQueryPage(sqlservice.Dialect(s.grant.Config.Dialect), statementSQL, opts)
	if err == nil && (s.grant.Config.Dialect == "mariadb" || s.grant.Config.Dialect == "oceanbase-mysql") {
		_, reason, vendorErr := browseVendorContext(e.ctx, c, s.grant.Config.Dialect, opts)
		if vendorErr != nil {
			err = vendorErr
		} else if reason != "" {
			err = errors.New(reason)
		}
	}
	if err == nil {
		err = validateQueryPageSources(e.ctx, c, s.grant.Config.Dialect, q)
	}
	if err != nil {
		reason := err.Error()
		q, err = sqlservice.AnalyzeQueryPageForReexecution(sqlservice.Dialect(s.grant.Config.Dialect), statementSQL, opts)
		if err != nil {
			p.view.Reason = reason
			return
		}
		p.view.RequiresReexecution = true
	}
	p.context, err = queryPageContext(e.ctx, c, s.grant.Config.Dialect)
	if err != nil {
		p.view.Reason = err.Error()
		return
	}
	p.query = q
	p.view.Supported = true
	p.view.ScopeID = newID("qpage_")
	p.view.Ordered = q.Ordered
}
func validateQueryPage(ctx context.Context, c *console, d string, p *queryPagePlan, opts sqlservice.AnalyzeOptions) error {
	current, err := queryPageContext(ctx, c, d)
	if err != nil {
		return err
	}
	if current != p.context || opts != p.options {
		return errors.New("Query context changed; run the original SQL again before enabling database paging")
	}
	if p.view.RequiresReexecution {
		if !p.reexecutionConfirmed {
			return errors.New("Explicit query reexecution has not been confirmed")
		}
		_, err := sqlservice.AnalyzeQueryPageForReexecution(sqlservice.Dialect(d), p.original, opts)
		return err
	}
	return validateQueryPageSources(ctx, c, d, p.query)
}

var queryCountDecimal = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)

func (v *Server) collectQueryCount(s *session, e *execution, rows *sql.Rows) error {
	defer rows.Close()
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return err
		}
		return errors.New("Count returned no value")
	}
	var total string
	if err := rows.Scan(&total); err != nil {
		return err
	}
	if !queryCountDecimal.MatchString(total) {
		return errors.New("Count did not return an exact nonnegative integer")
	}
	if rows.Next() {
		return errors.New("Count returned multiple rows")
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	v.update(s, e, func(x *ExecutionView) {
		x.QueryCount = &QueryCount{SourceResultID: e.queryPage.sourceResultID, ScopeID: e.queryPage.view.ScopeID, Total: total}
	})
	return nil
}
func queryPageAudit(p *queryPagePlan) string {
	return fmt.Sprintf(" query_scope=%s source_result=%s action=%s confirmed_reexecution=%t generated_sql=%q", p.view.ScopeID, p.sourceResultID, p.action, p.reexecutionConfirmed, p.generated)
}

// Replay is restricted to ordinary tables with built-in PostgreSQL types and
// no RLS. Views/foreign tables can hide arbitrary function calls from the source
// AST. A CTE name that also resolves to an unsafe table is conservatively denied.
func validateQueryPageSources(ctx context.Context, c *console, d string, q *sqlservice.QueryPageSQL) error {
	if len(q.Relations) > 128 || len(q.Functions) > 128 || len(q.Operators) > 128 || len(q.Types) > 128 {
		return errors.New("Query paging source inspection exceeds its limit")
	}
	seen := map[string]bool{}
	for _, r := range q.Relations {
		key := r.Schema + "\x00" + r.Name
		if seen[key] {
			continue
		}
		seen[key] = true
		var safe bool
		var err error
		cteName := r.Name
		if d == "sqlserver" {
			cteName = strings.ToLower(cteName)
		}
		if d == "postgresql" {
			qualified := sqlQuoteIdentifier(r.Name)
			if r.Schema != "" {
				qualified = sqlQuoteIdentifier(r.Schema) + "." + qualified
			}
			err = c.conn.QueryRowContext(ctx, `SELECT c.relkind IN ('r','m') AND NOT c.relrowsecurity AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent=c.oid) AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND n.nspname<>'pg_catalog') FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass($1)`, qualified).Scan(&safe)
		} else if d != "mysql" && d != "mariadb" && d != "oceanbase-mysql" {
			err = vendorQueryPageSource(ctx, c, d, r, &safe)
		} else {
			cteName = strings.ToLower(cteName)
			query := `SELECT TABLE_TYPE='BASE TABLE' AND ENGINE IN ('InnoDB','MyISAM','MEMORY','Aria') FROM information_schema.TABLES WHERE TABLE_SCHEMA=COALESCE(NULLIF(?,''),DATABASE()) AND TABLE_NAME=?`
			if d == "mariadb" {
				query = strings.Replace(query, "TABLE_TYPE='BASE TABLE'", "TABLE_TYPE IN ('BASE TABLE','SYSTEM VERSIONED')", 1)
			}
			err = c.conn.QueryRowContext(ctx, query, r.Schema, r.Name).Scan(&safe)
		}
		if errors.Is(err, sql.ErrNoRows) && (d == "postgresql" || sqlservice.IsMySQLDialect(sqlservice.Dialect(d))) && r.Schema == "" && q.CTEs[cteName] {
			continue
		}
		if err != nil || !safe {
			return errors.New("Database paging requires visible ordinary tables; views, inherited/partitioned or foreign tables, row security and unverified types cannot be safely replayed")
		}
	}
	if d != "postgresql" && d != "mysql" && d != "mariadb" && d != "oceanbase-mysql" {
		if err := validateVendorQueryFunctions(ctx, c, d, q.Functions); err != nil {
			return err
		}
		if sqlservice.IsOracleDialect(sqlservice.Dialect(d)) {
			if err := validateOracleColumnBindings(ctx, c, d, q.Scopes); err != nil {
				return err
			}
		}

	}

	if d == "postgresql" && len(q.Functions) > 0 {
		seen = map[string]bool{}
		for _, name := range q.Functions {
			if seen[name] {
				continue
			}
			seen[name] = true
			var shadow bool
			err := c.conn.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE p.proname=$1 AND n.nspname<>'pg_catalog' AND pg_catalog.pg_function_is_visible(p.oid))`, name).Scan(&shadow)
			if err != nil || shadow {
				return errors.New("Database paging cannot prove the selected function resolves only to a built-in")
			}
		}
	}
	if d == "postgresql" && len(q.Operators) > 0 {
		seen = map[string]bool{}
		for _, name := range q.Operators {
			if seen[name] {
				continue
			}
			seen[name] = true
			var shadow bool
			err := c.conn.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_operator o JOIN pg_catalog.pg_namespace n ON n.oid=o.oprnamespace WHERE o.oprname=$1 AND n.nspname<>'pg_catalog' AND pg_catalog.pg_operator_is_visible(o.oid))`, name).Scan(&shadow)
			if err != nil || shadow {
				return errors.New("Database paging cannot prove the selected operator resolves only to a built-in")
			}
		}
	}
	if d == "postgresql" && len(q.Types) > 0 {
		seen = map[string]bool{}
		for _, name := range q.Types {
			if seen[name] {
				continue
			}
			seen[name] = true
			var shadow bool
			err := c.conn.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE t.typname=$1 AND n.nspname<>'pg_catalog' AND pg_catalog.pg_type_is_visible(t.oid))`, name).Scan(&shadow)
			if err != nil || shadow {
				return errors.New("Database paging cannot prove the selected type resolves only to a built-in")
			}
		}
	}
	return nil
}
func sqlQuoteIdentifier(name string) string { return `"` + strings.ReplaceAll(name, `"`, `""`) + `"` }
