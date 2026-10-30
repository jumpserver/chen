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
	"slices"
	"strconv"
	"strings"

	"github.com/jumpserver/chen/internal/sqlservice"
)

const browseMaxOffset = 1000000

type browseOrder struct {
	Column string `json:"column"`
	Desc   bool   `json:"desc"`
}
type browseFilter struct {
	Op       string         `json:"op"`
	Column   string         `json:"column,omitempty"`
	Values   []string       `json:"values,omitempty"`
	Children []browseFilter `json:"children,omitempty"`
}
type browseRequest struct {
	Schema          string        `json:"schema"`
	Table           string        `json:"table"`
	SourceResultID  string        `json:"source_result_id,omitempty"`
	Action          string        `json:"action,omitempty"` // page (default), append, count, export
	Where           string        `json:"where,omitempty"`
	Filter          *browseFilter `json:"filter,omitempty"`
	Sort            []browseOrder `json:"sort,omitempty"`
	Offset          int           `json:"offset"`
	PageSize        int           `json:"page_size"`
	Format          string        `json:"format,omitempty"`
	RequestID       string        `json:"request_id"`
	SubmitReview    bool          `json:"submit_review"`
	ConfirmWarnings bool          `json:"confirm_warnings"`
}
type BrowsePage struct {
	MaxLoadedRows int           `json:"max_loaded_rows"`
	LimitReached  string        `json:"limit_reached,omitempty"` // rows or bytes; not end of table
	Supported     bool          `json:"supported"`
	Reason        string        `json:"reason,omitempty"`
	Database      string        `json:"database"`
	Schema        string        `json:"schema"`
	Table         string        `json:"table"`
	Where         string        `json:"where"`
	Filter        *browseFilter `json:"filter,omitempty"`
	Sort          []browseOrder `json:"sort"`
	Offset        int           `json:"offset"`
	PageSize      int           `json:"page_size"`
	MaxPageSize   int           `json:"max_page_size"`
	MaxOffset     int           `json:"max_offset"`
	Stable        bool          `json:"stable"`
	HasMore       *bool         `json:"has_more"` // null means the byte budget cut this page short
	FullSQL       string        `json:"full_sql"`
}
type BrowseCount struct {
	SourceResultID string `json:"source_result_id"`
	Total          string `json:"total"`
}
type browsePlan struct {
	fetchSize                                                        int // final append may be smaller than the selected batch size
	view                                                             BrowsePage
	table                                                            sqlservice.EditTable
	options                                                          sqlservice.AnalyzeOptions
	sql, policySQL, fullSQL, fullPolicySQL, countSQL, countPolicySQL string
	args                                                             []any
	fingerprint, action, sourceResultID                              string
}

func (v *Server) browseTable(w http.ResponseWriter, r *http.Request) {
	var req browseRequest
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
	// Recover before touching the connection: an in-flight retry is the same
	// execution even when metadata or session SQL mode have since changed.
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	c := v.findConsole(w, r, s)
	if c == nil {
		return
	}
	s.mu.Lock()
	old := c.requests[req.RequestID]
	s.mu.Unlock()
	if old != nil {
		if old.browse == nil || old.browse.plan == nil || old.browse.plan.fingerprint != string(fingerprint) {
			fail(w, 409, "idempotency_conflict", "Request ID belongs to another browse operation")
			return
		}
		writeJSON(w, 202, old.snapshot())
		return
	}
	if req.Action == "" {
		req.Action = "page"
	}
	if req.Action != "page" && req.Action != "append" && req.Action != "count" && req.Action != "export" {
		fail(w, 400, "invalid_browse", "Unknown browse operation")
		return
	}
	if req.Action == "export" && !s.grant.Permissions.Download {
		fail(w, 403, "download_permission_denied", "Result download is not permitted")
		return
	}
	if req.Offset < 0 || req.Offset > browseMaxOffset || req.PageSize < 0 || req.PageSize > min(1000, v.opts.MaxRows) {
		fail(w, 400, "invalid_browse", "Page size or offset exceeds the browse limit")
		return
	}
	s, c, ctx, done := v.editConsole(w, r)
	if s == nil {
		return
	}
	p, err := v.resolveBrowse(ctx, s, c, req)
	if err == nil {
		err = v.auditCatalog(ctx, s, c, "table-browse")
	}
	done()
	if err != nil {
		fail(w, 400, "invalid_browse", err.Error())
		return
	}
	p.fingerprint = string(fingerprint)
	body, _ := json.Marshal(map[string]any{"sql": p.policySQL, "request_id": req.RequestID, "submit_review": req.SubmitReview, "confirm_warnings": req.ConfirmWarnings, "format": req.Format})
	next := context.WithValue(r.Context(), editBrowseKey{}, &editBrowse{Schema: p.view.Schema, Table: p.view.Table, plan: p})
	if req.Action == "export" {
		next = context.WithValue(next, exportRequestContextKey{}, true)
	}
	r = r.Clone(next)
	r.Body = io.NopCloser(bytes.NewReader(body))
	v.submit(w, r)
}

func (v *Server) resolveBrowse(ctx context.Context, s *session, c *console, req browseRequest) (*browsePlan, error) {
	d := s.grant.Config.Dialect
	scope, err := liveContext(ctx, c, d)
	if err != nil {
		return nil, err
	}
	p := &browsePlan{action: req.Action, sourceResultID: req.SourceResultID}
	if req.SourceResultID != "" {
		s.mu.Lock()
		result := s.results[req.SourceResultID]
		s.mu.Unlock()
		if result == nil || result.consoleID != c.id || result.browse == nil || result.browse.plan == nil {
			return nil, errors.New("Browse result does not belong to this console")
		}
		previous := result.browse.plan
		if !previous.view.Supported {
			return nil, errors.New("Database paging, filtering, counting and full filtered export are currently supported for PostgreSQL and MySQL only")
		}
		if scope.Database != previous.view.Database {
			return nil, errors.New("Database changed; reopen this table")
		}
		if req.Where != "" || req.Filter != nil || len(req.Sort) != 0 || req.Schema != "" || req.Table != "" {
			return nil, errors.New("A result-bound request cannot replace its filter or target")
		}
		*p = *previous
		p.action = req.Action
		p.sourceResultID = req.SourceResultID
		p.view.Offset = req.Offset
		p.fetchSize = 0
		if req.Action == "append" && req.PageSize != 0 && req.PageSize != previous.view.PageSize {
			return nil, errors.New("Restart browsing before changing the batch size")
		}
		// Changing page size creates a fresh scope at the first page.
		if req.PageSize != 0 && req.PageSize != p.view.PageSize {
			p.view.PageSize = req.PageSize
			p.view.Offset = 0
		}
	} else {
		if req.Action != "page" {
			return nil, errors.New("Count and full export require an applied browse result")
		}
		if req.Table == "" {
			return nil, errors.New("Select a table to browse")
		}
		table, err := sqlservice.LoadEditTable(ctx, c.conn, sqlservice.Dialect(d), scope, req.Schema, req.Table)
		if err != nil {
			return nil, errors.New("This table is unavailable for editable browsing; open it as a normal query")
		}
		p.table = table
		p.view = BrowsePage{Supported: d == "postgresql" || d == "mysql", Database: table.Database, Schema: table.Schema, Table: table.Table, Where: strings.TrimSpace(req.Where), Filter: req.Filter, Sort: append([]browseOrder{}, req.Sort...), Offset: req.Offset, PageSize: req.PageSize, MaxPageSize: min(1000, v.opts.MaxRows), MaxOffset: browseMaxOffset, MaxLoadedRows: v.opts.MaxRows}
		if p.view.PageSize == 0 {
			p.view.PageSize = min(200, p.view.MaxPageSize)
		}
		p.options, err = liveOptions(ctx, c, d)
		if err != nil {
			return nil, err
		}
		if !p.view.Supported {
			if p.view.Where != "" || p.view.Filter != nil || len(p.view.Sort) > 0 || p.view.Offset != 0 || req.PageSize != 0 {
				return nil, errors.New("Database filtering and paging currently support PostgreSQL and MySQL only; this dialect retains cached browsing")
			}
			p.view.Reason = "legacy_cached_browse"
			p.policySQL = "SELECT * FROM " + editQualified(table.Schema, table.Table, d)
			switch d {
			case "mariadb", "oceanbase-mysql":
				p.policySQL += " LIMIT " + strconv.Itoa(v.opts.MaxRows+1)
			case "sqlserver":
				p.policySQL = "SELECT TOP (" + strconv.Itoa(v.opts.MaxRows+1) + ") * FROM " + editQualified(table.Schema, table.Table, d)
			default:
				p.policySQL += " FETCH FIRST " + strconv.Itoa(v.opts.MaxRows+1) + " ROWS ONLY"
			}
			p.sql = p.policySQL
			return p, nil
		}
		if p.view.Where != "" && p.view.Filter != nil {
			return nil, errors.New("Choose structured conditions or a handwritten WHERE")
		}
		if err = validateBrowseWhere(ctx, c, d, p); err != nil {
			return nil, err
		}
		predicate, policy := "", ""
		if p.view.Filter != nil {
			budget := 100
			predicate, policy, err = buildBrowseFilter(d, p.table, *p.view.Filter, &p.args, 0, &budget)
			if err != nil {
				return nil, err
			}
		} else if p.view.Where != "" {
			predicate = "(\n" + p.view.Where + "\n)"
			policy = predicate
		}
		if len(p.view.Sort) > 8 {
			return nil, errors.New("At most eight sort columns are supported")
		}
		order := []string{}
		seen := map[string]bool{}
		p.view.Sort = append([]browseOrder{}, p.view.Sort...)
		for i, item := range p.view.Sort {
			col := browseColumn(p.table, item.Column)
			if col == nil || seen[col.Name] {
				return nil, errors.New("Sort columns must be distinct visible table columns")
			}
			seen[col.Name] = true
			p.view.Sort[i].Column = col.Name
			part := quoteDatabaseIdentifier(col.Name, d)
			if item.Desc {
				part += " DESC"
			}
			order = append(order, part)
		}
		for _, key := range p.table.PrimaryKey {
			if !seen[key] {
				order = append(order, quoteDatabaseIdentifier(key, d))
				p.view.Sort = append(p.view.Sort, browseOrder{Column: key})
			}
		}
		p.view.Stable = len(p.table.PrimaryKey) > 0
		// ponytail: unique-index discovery is not shared with grid metadata yet.
		// Tables without a proven PK remain readable, with explicit offset instability.
		base := " FROM " + editQualified(p.view.Schema, p.view.Table, d)
		p.fullSQL = "SELECT *" + base
		p.fullPolicySQL = p.fullSQL
		p.countSQL = "SELECT COUNT(*)" + base
		p.countPolicySQL = p.countSQL
		if predicate != "" {
			p.fullSQL += " WHERE " + predicate
			p.countSQL += " WHERE " + predicate
			p.fullPolicySQL += " WHERE " + policy
			p.countPolicySQL += " WHERE " + policy
		}
		if len(order) > 0 {
			p.fullSQL += " ORDER BY " + strings.Join(order, ", ")
			p.fullPolicySQL += " ORDER BY " + strings.Join(order, ", ")
		}
		p.view.FullSQL = p.fullPolicySQL
	}
	if req.Action == "append" {
		previous, err := v.browseAppendSource(s, c.id, p)
		if err != nil {
			return nil, err
		}
		p.fetchSize = min(p.view.PageSize, v.opts.MaxRows-len(previous.Rows))
	}
	switch req.Action {
	case "count":
		p.sql, p.policySQL = p.countSQL, p.countPolicySQL
	case "export":
		p.sql, p.policySQL = p.fullSQL, p.fullPolicySQL
	default:
		size := p.view.PageSize
		if p.fetchSize > 0 {
			size = p.fetchSize
		}
		suffix := fmt.Sprintf(" LIMIT %d OFFSET %d", size+1, p.view.Offset)
		p.sql, p.policySQL = p.fullSQL+suffix, p.fullPolicySQL+suffix
	}
	return p, nil
}

func validateBrowseWhere(ctx context.Context, c *console, d string, p *browsePlan) error {
	if p.view.Where == "" {
		return nil
	}
	if d == "postgresql" {
		var standard string
		if err := c.conn.QueryRowContext(ctx, "SHOW standard_conforming_strings").Scan(&standard); err != nil {
			return err
		}
		if standard != "on" {
			return errors.New("Handwritten WHERE requires standard_conforming_strings=on; use structured conditions")
		}
	}
	return sqlservice.ValidateBrowseWhere(sqlservice.Dialect(d), p.view.Where, p.options, p.table.Columns)
}

func browseColumn(table sqlservice.EditTable, name string) *sqlservice.EditColumn {
	for i := range table.Columns {
		if table.Columns[i].Name == name {
			return &table.Columns[i]
		}
	}
	return nil
}
func buildBrowseFilter(d string, table sqlservice.EditTable, f browseFilter, args *[]any, depth int, budget *int) (string, string, error) {
	*budget--
	bad := errors.New("Invalid structured filter: use visible columns, bounded AND/OR groups and comparison values")
	if depth > 8 || *budget < 0 {
		return "", "", bad
	}
	if f.Op == "and" || f.Op == "or" {
		if len(f.Children) == 0 || f.Column != "" || len(f.Values) != 0 {
			return "", "", bad
		}
		parts, policies := []string{}, []string{}
		for _, child := range f.Children {
			sql, policy, err := buildBrowseFilter(d, table, child, args, depth+1, budget)
			if err != nil {
				return "", "", err
			}
			parts = append(parts, sql)
			policies = append(policies, policy)
		}
		join := " " + strings.ToUpper(f.Op) + " "
		return "(" + strings.Join(parts, join) + ")", "(" + strings.Join(policies, join) + ")", nil
	}
	col := browseColumn(table, f.Column)
	if col == nil || len(f.Children) > 0 {
		return "", "", bad
	}
	name := quoteDatabaseIdentifier(col.Name, d)
	if f.Op == "is_null" || f.Op == "not_null" {
		if len(f.Values) != 0 {
			return "", "", bad
		}
		suffix := " IS NULL"
		if f.Op == "not_null" {
			suffix = " IS NOT NULL"
		}
		return name + suffix, name + suffix, nil
	}
	operators := map[string]string{"eq": "=", "ne": "<>", "lt": "<", "le": "<=", "gt": ">", "ge": ">=", "like": "LIKE", "not_like": "NOT LIKE", "in": "IN", "not_in": "NOT IN"}
	op, ok := operators[f.Op]
	if !ok || len(f.Values) == 0 || len(f.Values) > 100 || ((f.Op != "in" && f.Op != "not_in") && len(f.Values) != 1) {
		return "", "", bad
	}
	slots, literals := []string{}, []string{}
	for _, raw := range f.Values {
		if len(raw) > 4096 {
			return "", "", bad
		}
		value, err := convertEditValue(*col, &raw)
		if err != nil {
			return "", "", err
		}
		*args = append(*args, value)
		slots = append(slots, editPlaceholder(d, len(*args)))
		lit, err := copyTextLiteral(d, fmt.Sprint(value))
		if err != nil {
			return "", "", err
		}
		literals = append(literals, lit)
	}
	sql, policy := strings.Join(slots, ", "), strings.Join(literals, ", ")
	if f.Op == "in" || f.Op == "not_in" {
		sql = "(" + sql + ")"
		policy = "(" + policy + ")"
	}
	return name + " " + op + " " + sql, name + " " + op + " " + policy, nil
}

// Appends are offset-checked twice: at submission and under the worker's console
// lock. Two queued requests for the same segment cannot append it twice.
func (v *Server) browseAppendSource(s *session, consoleID string, p *browsePlan) (*Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := s.results[p.sourceResultID]
	if result == nil || result.consoleID != consoleID || result.editStale || result.Browse == nil || !result.Browse.Supported || result.Browse.Offset != 0 || p.view.Offset != len(result.Rows) {
		return nil, errors.New("Browse result changed; restart continuous scrolling")
	}
	if result.Truncated || result.Browse.HasMore == nil || !*result.Browse.HasMore || len(result.Rows) >= v.opts.MaxRows {
		return nil, errors.New("No further rows can be appended to this cached result")
	}
	return result, nil
}

func (v *Server) collectBrowseAppend(s *session, e *execution, rows *sql.Rows) error {
	defer rows.Close()
	p := e.browse.plan
	previous, err := v.browseAppendSource(s, e.view.ConsoleID, p)
	if err != nil {
		return err
	}
	batch, err := v.readRows(s, rows, p)
	if err != nil {
		return err
	}
	published := false
	defer func() {
		if !published {
			s.mu.Lock()
			s.resultBytes -= batch.memoryBytes
			s.mu.Unlock()
		}
	}()
	if rows.NextResultSet() {
		return errors.New("Continuous browsing requires one result set")
	}
	if err := rows.Err(); err != nil {
		return err
	}
	rows.Close()
	if !slices.Equal(previous.Columns, batch.Columns) {
		return errors.New("Table columns changed; refresh before continuing")
	}
	batch.consoleID, batch.sourceSQL, batch.browse = e.view.ConsoleID, e.resultSQL, e.browse
	batch.sourceAnalysis = e.resultAnalysis
	v.bindResultEditSource(s, e, []*Result{batch})
	// Publish a new snapshot under the same ID. Existing readers and cached
	// exports keep immutable prefix slices; cells and opaque row refs never move.
	merged := *previous
	merged.Rows = append(slices.Clone(previous.Rows), batch.Rows...)
	merged.original = append(slices.Clone(previous.original), batch.original...)
	merged.masked = append(slices.Clone(previous.masked), batch.masked...)
	merged.copyLossy = append(slices.Clone(previous.copyLossy), batch.copyLossy...)
	merged.rowRefs = append(slices.Clone(previous.rowRefs), batch.rowRefs...)
	merged.TotalRows = len(merged.Rows)
	merged.memoryBytes += batch.memoryBytes
	merged.Truncated = batch.Truncated
	view := *batch.Browse
	view.Offset = 0
	merged.Browse = &view
	if merged.Truncated {
		view.LimitReached = "bytes"
	} else if merged.TotalRows >= v.opts.MaxRows && view.HasMore != nil && *view.HasMore {
		view.LimitReached = "rows"
		merged.Truncated = true
	}
	s.mu.Lock()
	s.results[merged.ID] = &merged
	s.mu.Unlock()
	published = true
	v.update(s, e, func(x *ExecutionView) {
		x.ResultIDs = append(x.ResultIDs, merged.ID)
		x.StatementResults = append(x.StatementResults, StatementResult{ResultID: merged.ID})
	})
	return nil
}
