package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

func (v *Server) submit(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	c := v.findConsole(w, r, s)
	if c == nil {
		return
	}
	isExport, _ := r.Context().Value(exportRequestContextKey{}).(bool)
	browse, _ := r.Context().Value(editBrowseKey{}).(*editBrowse)
	if isExport && !s.grant.Permissions.Download {
		fail(w, 403, "download_permission_denied", "Result download is not permitted")
		return
	}
	var req struct {
		Format          string `json:"format"`
		SQL             string `json:"sql"`
		RequestID       string `json:"request_id"`
		DocumentVersion int    `json:"document_version"`
		SubmitReview    bool   `json:"submit_review"`
		ConfirmWarnings bool   `json:"confirm_warnings"`
	}
	if !decode(w, r, &req) {
		return
	}
	if !isExport && req.Format != "" {
		fail(w, 400, "invalid_request", "Export format requires the exports endpoint")
		return
	}
	if isExport && (req.Format != "csv" && req.Format != "xlsx") {
		fail(w, 400, "invalid_export_format", "Choose CSV or XLSX")
		return
	}
	if strings.TrimSpace(req.SQL) == "" || len(req.SQL) > sqlservice.MaxDocumentBytes {
		fail(w, 400, "invalid_sql", "SQL is empty or too large")
		return
	}
	if req.RequestID == "" {
		req.RequestID = r.Header.Get("Idempotency-Key")
	}
	if req.RequestID == "" || len(req.RequestID) > 128 {
		fail(w, 400, "request_id_required", "A stable request_id is required")
		return
	}
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil || s.consoles[c.id] != c || c.closed.Load() || c.lost.Load() || c.ctx.Err() != nil || s.grant.Check() != nil {
		s.mu.Unlock()
		fail(w, 409, "session_unavailable", "Session or console changed while submitting SQL")
		return
	}
	if old := c.requests[req.RequestID]; old != nil {
		s.mu.Unlock()
		if old.sql != req.SQL || old.submitReview != req.SubmitReview || old.confirmWarnings != req.ConfirmWarnings || (old.export != nil) != isExport || (old.export != nil && old.export.format != req.Format) || (old.browse != nil) != (browse != nil) || old.edit != nil {
			fail(w, 409, "idempotency_conflict", "Request ID already belongs to different SQL")
			return
		}
		writeJSON(w, 202, old.snapshot())
		return
	}
	if len(s.executions) >= 256 {
		s.mu.Unlock()
		fail(w, 429, "execution_limit", "Session execution history limit reached; open a new session")
		return
	}
	ctx, cancel := context.WithCancel(c.ctx)
	e := &execution{view: ExecutionView{ID: newID("exe_"), ConsoleID: c.id, Status: "queued", ResultIDs: []string{}, StatementResults: []StatementResult{}, AuditStatus: "pending"}, sql: req.SQL, documentVersion: req.DocumentVersion, ctx: ctx, cancel: cancel, cancelDone: make(chan struct{}), submitReview: req.SubmitReview, confirmWarnings: req.ConfirmWarnings}
	e.browse = browse
	if e.browse != nil {
		e.view.SQL = req.SQL
	}
	if isExport {
		j, err := v.newExportLocked(s, req.Format, "query", e)
		if err != nil {
			s.mu.Unlock()
			cancel()
			exportAPIError(w, err)
			return
		}
		e.export = j
		e.view.ExportID = j.id
		e.startDeadline(10 * time.Minute)
	} else {
		e.startDeadline(v.opts.QueryTimeout)
	}
	select {
	case c.queue <- e:
		s.executions[e.view.ID] = e
		c.requests[req.RequestID] = e
	default:
		s.mu.Unlock()
		cancel()
		e.stopDeadline()
		if e.export != nil {
			e.export.cleanup(false)
		}
		fail(w, 429, "queue_full", "Console queue is full")
		return
	}
	s.mu.Unlock()
	s.grant.Touch()
	s.emit("execution.updated", e.snapshot())
	writeJSON(w, 202, e.snapshot())
}
func (v *Server) executionStatus(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, true)
	if s == nil {
		return
	}
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil {
		s.mu.Unlock()
		fail(w, 410, "session_closed", "Session is closed")
		return
	}
	e := s.executions[r.PathValue("eid")]
	s.mu.Unlock()
	if e == nil {
		fail(w, 404, "not_found", "Execution not found")
		return
	}
	writeJSON(w, 200, e.snapshot())
}
func (v *Server) cancelExecution(w http.ResponseWriter, r *http.Request) {
	v.cancelExecutionWithResponse(w, r, func(view ExecutionView) any { return view })
}

// Export cancellation shares the execution policy and state transition, while
// returning the export resource expected by the endpoint's client.
func (v *Server) cancelExecutionWithResponse(w http.ResponseWriter, r *http.Request, response func(ExecutionView) any) {
	s := v.auth(w, r, true)
	if s == nil {
		return
	}
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil {
		s.mu.Unlock()
		fail(w, 410, "session_closed", "Session is closed")
		return
	}
	e := s.executions[r.PathValue("eid")]
	var c *console
	if e != nil {
		c = s.consoles[e.view.ConsoleID]
	}
	s.mu.Unlock()
	if e == nil {
		fail(w, 404, "not_found", "Execution not found")
		return
	}
	e.mu.Lock()
	if terminal(e.view.Status) {
		view := e.view
		e.mu.Unlock()
		writeJSON(w, 200, response(view))
		return
	}
	if !supportsActiveCancellation(s.grant.Config.Dialect) && e.view.Status != "queued" && e.view.Status != "waiting_review" {
		e.mu.Unlock()
		fail(w, 409, "cancel_unsupported", "The installed database driver cannot cancel an active query")
		return
	}
	wasQueued := e.view.Status == "queued" || e.view.Status == "waiting_review"
	e.cancelRequested.Store(true)
	e.view.Status = "cancel_requested"
	if wasQueued {
		e.cancel()
	}
	e.mu.Unlock()
	s.emit("execution.updated", e.snapshot())
	e.cancelOnce.Do(func() {
		go func() {
			defer close(e.cancelDone)
			defer e.cancel()
			if wasQueued || c == nil {
				return
			}
			// Use a separate control connection. The worker waits for this control
			// operation before it can start another statement on this connection.
			ctx, stop := context.WithTimeout(context.Background(), 2*time.Second)
			defer stop()
			cancelBackend(ctx, s, c, e)
			select {
			case <-time.After(75 * time.Millisecond):
			case <-ctx.Done():
			}
		}()
	})
	writeJSON(w, 202, response(e.snapshot()))
}
func (v *Server) worker(s *session, c *console) {
	defer s.workers.Done()
	for {
		select {
		case e := <-c.queue:
			v.run(s, c, e)
		case <-c.ctx.Done():
			for {
				select {
				case e := <-c.queue:
					v.run(s, c, e)
				default:
					return
				}
			}
		}
	}
}
func (v *Server) update(s *session, e *execution, fn func(*ExecutionView)) {
	e.mu.Lock()
	fn(&e.view)
	e.mu.Unlock()
	s.emit("execution.updated", e.snapshot())
	if e.export != nil {
		e.export.emit()
	}
}
func (v *Server) run(s *session, c *console, e *execution) {
	if e.edit != nil {
		v.runEdits(s, c, e)
		return
	}
	c.mu.Lock()
	// A canceled mysql driver context closes the client TCP connection; that
	// alone does not prove that a server-side write stopped. Cover timeouts,
	// admin/session/console closure as well as the explicit HTTP cancel path.
	var databaseTouched atomic.Bool
	databaseCancelDone := make(chan struct{})
	stopDatabaseCancel := context.AfterFunc(e.ctx, func() {
		defer close(databaseCancelDone)
		if (!jumpserver.IsMySQLWire(s.grant.Config.Dialect) && s.grant.Config.Dialect != "clickhouse") || !databaseTouched.Load() || e.cancelRequested.Load() {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		cancelBackend(ctx, s, c, e)
	})
	defer func() {
		// Stop the callback before normal completion cancels e.ctx. Join a
		// running KILL before releasing the connection to any next execution.
		if !stopDatabaseCancel() {
			<-databaseCancelDone
		}
		e.stopDeadline()
		e.cancel()
		if e.cancelRequested.Load() {
			<-e.cancelDone
		}
		c.mu.Unlock()
	}()
	start := time.Now()
	audit := jumpserver.AuditRecord{ID: e.view.ID, SQL: e.sql, StartedAt: start, RiskClassified: true}
	var runErr error
	currentInFlight := false
	completedStatements := 0
	confirmedCancel := false
	triggeredWarnings := map[string]jumpserver.AuditRecord{}
	defer func() {
		audit.FinishedAt = time.Now()
		if runErr != nil {
			audit.Error = runErr.Error()
		}
		view := e.snapshot()
		audit.Output = fmt.Sprintf("execution_id=%s state=%s completed_statements=%d result_sets=%d", view.ID, view.Status, completedStatements, len(view.ResultIDs))
		if e.export != nil {
			out := e.export.snapshot()
			audit.Output += fmt.Sprintf(" export_id=%s format=%s rows=%d bytes=%d", out.ID, out.Format, out.Rows, out.Bytes)
		}
		if runErr != nil {
			if view.Error != nil {
				audit.Error = audit.Output + "; " + view.Error.Message
			} else {
				audit.Error = audit.Output + "; " + runErr.Error()
			}
		}
		ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		status := "recorded"
		if err := v.provider.Record(ctx, s.grant, audit); err != nil {
			status = "failed"
		}
		for _, warning := range triggeredWarnings {
			if audit.RiskLevel == warning.RiskLevel && audit.ACLID == warning.ACLID && audit.GroupID == warning.GroupID {
				continue
			}
			warning.StartedAt, warning.FinishedAt = audit.StartedAt, audit.FinishedAt
			warning.Output = audit.Output + "; policy_warning"
			warning.Error = audit.Error
			if err := v.provider.Record(ctx, s.grant, warning); err != nil {
				status = "failed"
			}
		}
		end := time.Now()
		v.update(s, e, func(x *ExecutionView) { x.AuditStatus = status; x.FinishedAt = &end })
		if err := v.journal(s, e, "finished"); err != nil {
			v.update(s, e, func(x *ExecutionView) { x.AuditStatus = "failed" })
		}
		if e.export != nil {
			e.export.finishExecution()
		}
		if databaseTouched.Load() && runErr != nil && !c.closed.Load() {
			ping, done := context.WithTimeout(context.Background(), time.Second)
			defer done()
			if c.conn.PingContext(ping) != nil {
				c.lost.Store(true)
				s.emit("console.updated", ConsoleView{c.id, c.context.Database, c.context.Schema, "lost"})
			}
		}
	}()
	failure := func(err error, api *APIError) {
		if errors.Is(err, context.Canceled) && e.deadlineExpired.Load() {
			err = context.DeadlineExceeded
			api = safeDatabaseError(err)
		}
		runErr = err
		// PostgreSQL conversion/constraint errors can quote original field
		// values (for example protected_text::integer). A result-only mask would
		// otherwise leak that value through an error response and command audit.
		if isServerError(err) {
			if _, masked := v.provider.Mask(s.grant, "", "", api.Message); masked {
				api = &APIError{api.Code, "Database diagnostic details are hidden by the data masking policy"}
			}
		}
		status := "failed"
		if confirmedCancel {
			status = "canceled"
		} else if currentInFlight {
			if !isServerError(err) {
				status = "unknown"
				api = &APIError{"execution_unknown", "The database outcome is unknown; do not automatically repeat this SQL"}
			}
		} else if e.ctx.Err() != nil || e.cancelRequested.Load() {
			status = "canceled"
		}
		if completedStatements > 0 {
			message := fmt.Sprintf("%d earlier statement(s) completed and may already have taken effect. Do not automatically repeat the whole script. ", completedStatements)
			if status == "unknown" {
				api = &APIError{"execution_unknown", message + api.Message}
			} else {
				code := "script_partial_failure"
				if status == "canceled" {
					code = "script_partial_cancel"
				}
				api = &APIError{code, message + api.Message}
			}
		}
		v.update(s, e, func(x *ExecutionView) { x.Status = status; x.Error = api })
	}
	if err := e.ctx.Err(); err != nil {
		failure(err, safeDatabaseError(err))
		return
	}
	if c.lost.Load() || c.closed.Load() {
		failure(errors.New("connection_lost"), &APIError{"connection_lost", "Open a new console; connection state was lost"})
		return
	}
	if err := s.grant.Check(); err != nil {
		failure(err, &APIError{"session_unavailable", err.Error()})
		return
	}
	s.grant.SetBusy(true)
	defer s.grant.SetBusy(false)
	if err := v.journal(s, e, "accepted"); err != nil {
		failure(err, &APIError{"audit_persistence_failed", "Execution intent could not be recorded; SQL was not sent"})
		return
	}
	if jumpserver.IsMySQLWire(s.grant.Config.Dialect) {
		databaseTouched.Store(true)
	}
	opts, err := liveOptions(e.ctx, c, s.grant.Config.Dialect)
	if err != nil {
		failure(err, safeDatabaseError(err))
		return
	}
	analysis, err := sqlservice.AnalyzeWithOptions(sqlservice.Dialect(s.grant.Config.Dialect), e.sql, opts)
	if err != nil {
		failure(err, &APIError{"sql_parse_error", err.Error()})
		return
	}
	if len(analysis.Statements) == 0 || len(analysis.Statements) > 100 {
		failure(errors.New("statement_limit"), &APIError{"statement_limit", "A script must contain between 1 and 100 statements"})
		return
	}
	if e.export != nil {
		if !s.grant.Permissions.Download {
			failure(errors.New("download_permission_denied"), &APIError{"download_permission_denied", "Result download is not permitted"})
			return
		}
		if len(analysis.Statements) != 1 || analysis.Statements[0].MayWrite || !analysis.Statements[0].ReturnsRows || (analysis.Statements[0].Kind != "select" && analysis.Statements[0].Kind != "setopr") {
			failure(errors.New("export_requires_single_select"), &APIError{"export_requires_single_select", "Full export requires exactly one read query"})
			return
		}
	}
	// Validate the complete script and each original statement before sending any
	// statement, including generated and multi-statement SQL.
	if err := v.authorizeExecution(s, e, append([]string{e.sql}, statementSQL(analysis.Statements)...), &audit); err != nil {
		var pe *jumpserver.PolicyError
		code := "command_denied"
		if errors.As(err, &pe) {
			audit.RiskLevel = pe.RiskLevel
			// A consent prompt is not an executed warning and must not notify reviewers.
			if pe.Code == "command_warning_confirmation_required" {
				audit.RiskLevel = 0
			}
			audit.ACLID = pe.ACLID
			audit.GroupID = pe.GroupID
			code = pe.Code
		}
		failure(err, &APIError{code, err.Error()})
		return
	}
	executionStart := time.Now()
	var startErr error
	v.update(s, e, func(x *ExecutionView) {
		startErr = e.ctx.Err()
		if startErr == nil && e.cancelRequested.Load() {
			startErr = context.Canceled
		}
		if startErr == nil {
			x.Status, x.StartedAt = "running", &executionStart
		}
	})
	if startErr != nil {
		failure(startErr, safeDatabaseError(startErr))
		return
	}
	for _, statement := range analysis.Statements {
		if err := e.ctx.Err(); err != nil {
			failure(err, safeDatabaseError(err))
			return
		}
		if e.cancelRequested.Load() {
			failure(context.Canceled, &APIError{"query_canceled", "Query cancellation requested"})
			return
		}
		if err := s.grant.Check(); err != nil {
			failure(err, &APIError{"session_unavailable", err.Error()})
			return
		}
		current, err := liveOptions(e.ctx, c, s.grant.Config.Dialect)
		if err != nil {
			failure(err, safeDatabaseError(err))
			return
		}
		// AST facts remain valid while parser options are unchanged. Reuse them
		// for dispatch and edit provenance instead of reparsing each consumer.
		if statement.Facts == nil || current != opts {
			checked, err := sqlservice.AnalyzeWithOptions(sqlservice.Dialect(s.grant.Config.Dialect), statement.SQL, current)
			if err != nil || len(checked.Statements) != 1 {
				failure(errors.New("sql_context_changed"), &APIError{"sql_context_changed", "Statement boundaries changed with the connection SQL mode; run statements separately"})
				return
			}

			statement = checked.Statements[0]
		}

		if e.cancelRequested.Load() {
			failure(context.Canceled, &APIError{"query_canceled", "Query cancellation requested"})
			return
		}
		for _, command := range []string{e.sql, statement.SQL} {
			if warning, ok := e.policyWarnings[command]; ok {
				key := warning.ACLID + ":" + warning.GroupID
				triggeredWarnings[key] = jumpserver.AuditRecord{ID: e.view.ID + ":warning:" + key, SQL: command, RiskLevel: warning.RiskLevel, ACLID: warning.ACLID, GroupID: warning.GroupID}
				if warning.RiskLevel > audit.RiskLevel {
					audit.RiskLevel, audit.ACLID, audit.GroupID = warning.RiskLevel, warning.ACLID, warning.GroupID
				}
			}
		}
		currentInFlight = true
		databaseTouched.Store(true)
		if statement.ReturnsRows {
			e.resultSQL = statement.SQL
			e.resultAnalysis = statement.Facts
			rows, err := c.conn.QueryContext(executionContext(e.ctx, s.grant.Config.Dialect, e.view.ID), statement.SQL)
			if err != nil {
				confirmedCancel = isServerCancellation(err) || driverConfirmedCancellation(s.grant.Config.Dialect, err)
				failure(err, safeDatabaseError(err))
				return
			}
			var collectErr error
			if e.export != nil {
				collectErr = v.collectExport(s, c, e, rows)
			} else {
				collectErr = v.collectResults(s, e, rows)
			}
			if err := collectErr; err != nil {
				// database/sql can replace a driver's Rows error with ctx.Err().
				// That alone does not prove the server acknowledged cancellation.
				confirmedCancel = isServerCancellation(err)
				failure(err, safeDatabaseError(err))
				return
			}
		} else {
			result, err := c.conn.ExecContext(executionContext(e.ctx, s.grant.Config.Dialect, e.view.ID), statement.SQL)
			if err != nil {
				confirmedCancel = isServerCancellation(err) || driverConfirmedCancellation(s.grant.Config.Dialect, err)
				failure(err, safeDatabaseError(err))
				return
			}
			affected, _ := result.RowsAffected()
			v.update(s, e, func(x *ExecutionView) {
				x.StatementResults = append(x.StatementResults, StatementResult{RowsAffected: strconv.FormatInt(affected, 10)})
			})
		}
		currentInFlight = false
		completedStatements++
	}
	v.update(s, e, func(x *ExecutionView) { x.Status = "succeeded" })
}
func statementSQL(stmts []sqlservice.Statement) []string {
	out := make([]string, 0, len(stmts))
	for _, s := range stmts {
		out = append(out, s.SQL)
	}
	return out
}
func liveOptions(ctx context.Context, c *console, dialect string) (sqlservice.AnalyzeOptions, error) {
	var o sqlservice.AnalyzeOptions
	if jumpserver.IsMySQLDialect(dialect) {
		var version string
		if err := c.conn.QueryRowContext(ctx, "SELECT @@session.sql_mode, VERSION()").Scan(&o.MySQLSQLMode, &version); err != nil {
			return o, err
		}
		var major, minor, patch int
		_, _ = fmt.Sscanf(version, "%d.%d.%d", &major, &minor, &patch)
		o.MySQLVersion = major*10000 + minor*100 + patch
	}
	return o, nil
}
func isServerCancellation(err error) bool {
	var me *mysql.MySQLError
	if errors.As(err, &me) {
		return me.Number == 1317
	}
	var pe *pgconn.PgError
	return errors.As(err, &pe) && pe.Code == "57014" || extraServerCancellation(err)
}

func cancelBackend(ctx context.Context, s *session, c *console, e *execution) {
	if s.db == nil {
		return
	}
	if jumpserver.IsMySQLWire(s.grant.Config.Dialect) {
		_, _ = s.db.ExecContext(ctx, "KILL QUERY "+strconv.FormatInt(c.backendID, 10))
	} else if s.grant.Config.Dialect == "postgresql" {
		_, _ = s.db.ExecContext(ctx, "SELECT pg_cancel_backend($1)", c.backendID)
	} else {
		cancelDriverExecution(ctx, s, c, e)
	}
}

func isServerError(err error) bool {
	var me *mysql.MySQLError
	var pe *pgconn.PgError
	return errors.As(err, &me) || errors.As(err, &pe) || extraDatabaseError(err) != nil
}

// A transport failure after dispatch cannot prove whether a write committed.
func isTransportError(err error) bool {
	if isDamengTransportError(err) {
		return true
	}
	var me *mysql.MySQLError
	var pe *pgconn.PgError
	if errors.As(err, &me) || errors.As(err, &pe) || extraDatabaseError(err) != nil {
		return false
	}
	var ne net.Error
	return errors.Is(err, driver.ErrBadConn) || errors.Is(err, sql.ErrConnDone) || errors.Is(err, mysql.ErrInvalidConn) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) || errors.As(err, &ne) || pgconn.SafeToRetry(err) == false && strings.Contains(err.Error(), "conn closed")
}
func binaryResultType(dbType string) bool {
	dbType = strings.ToUpper(dbType)
	return strings.Contains(dbType, "BLOB") || strings.Contains(dbType, "BINARY") || dbType == "BYTEA" || dbType == "RAW" || dbType == "LONG RAW" || dbType == "IMAGE"
}

func normalizeValue(value any, dbType string) any {
	dbType = strings.ToUpper(dbType)
	switch x := value.(type) {
	case int64:
		return strconv.FormatInt(x, 10)
	case uint64:
		return strconv.FormatUint(x, 10)
	case []byte:
		if x == nil {
			return nil
		}
		if binaryResultType(dbType) || !validTextBytes(x) {
			return "0x" + hex.EncodeToString(x)
		}
		return string(x)
	case time.Time:
		switch dbType {
		case "DATE":
			return x.Format("2006-01-02")
		case "DATETIME", "TIMESTAMP":
			return x.Format("2006-01-02 15:04:05.999999999")
		default:
			return x.Format(time.RFC3339Nano)
		}
	case float64:
		if math.IsNaN(x) {
			return "NaN"
		}
		if math.IsInf(x, 1) {
			return "Infinity"
		}
		if math.IsInf(x, -1) {
			return "-Infinity"
		}
		return x
	default:
		return normalizeComposite(value, dbType)
	}
}
func (v *Server) collectResults(s *session, e *execution, rows *sql.Rows) error {
	var collected []*Result
	defer func() {
		rows.Close()
		if e.resultSQL != "" {
			v.bindResultEditSource(s, e, collected)
		}
		for _, result := range collected {
			s.mu.Lock()
			s.results[result.ID] = result
			s.mu.Unlock()
			v.update(s, e, func(x *ExecutionView) {
				x.ResultIDs = append(x.ResultIDs, result.ID)
				x.StatementResults = append(x.StatementResults, StatementResult{ResultID: result.ID})
			})
		}
	}()
	for {
		result, err := v.readRows(s, rows)
		if err != nil {
			return err
		}
		result.consoleID, result.sourceSQL, result.browse = e.view.ConsoleID, e.resultSQL, e.browse
		result.sourceAnalysis = e.resultAnalysis
		collected = append(collected, result)
		if !rows.NextResultSet() {
			return rows.Err()
		}
	}
}
func (v *Server) readRows(s *session, rows *sql.Rows) (*Result, error) {
	reserved := 0
	complete := false
	defer func() {
		if !complete && reserved > 0 {
			s.mu.Lock()
			s.resultBytes -= reserved
			s.mu.Unlock()
		}
	}()
	types, err := rows.ColumnTypes()
	if err != nil {
		return nil, err
	}
	result := &Result{ID: newID("res_"), Columns: []Column{}, Rows: [][]any{}}
	for _, column := range types {
		result.Columns = append(result.Columns, Column{column.Name(), column.DatabaseTypeName()})
	}
	for rows.Next() {
		raw := make([]any, len(types))
		targets := make([]any, len(types))
		for i := range raw {
			targets[i] = &raw[i]
		}
		if err := rows.Scan(targets...); err != nil {
			return nil, err
		}
		copyLossy := make([]bool, len(raw))
		for i := range raw {
			if data, ok := raw[i].([]byte); ok && data != nil && !binaryResultType(types[i].DatabaseTypeName()) && !validTextBytes(data) {
				copyLossy[i] = true
			}
			raw[i], err = materializeValue(raw[i], types[i].DatabaseTypeName(), v.opts.MaxResultBytes)
			if err != nil {
				return nil, err
			}
		}
		original := append([]any(nil), raw...)
		masked := make([]bool, len(raw))
		extra := 0
		for i := range raw {
			raw[i], masked[i] = v.provider.Mask(s.grant, "", types[i].Name(), raw[i])
			if masked[i] {
				b, encodeErr := json.Marshal(original[i])
				if encodeErr != nil {
					return nil, encodeErr
				}
				extra += len(b)
			}
		}
		encoded, err := json.Marshal(raw)
		if err != nil {
			return nil, err
		}
		s.mu.Lock()
		space := s.resultBytes+len(encoded)+extra <= v.opts.MaxResultBytes
		if len(result.Rows) < v.opts.MaxRows && space {
			s.resultBytes += len(encoded) + extra
			reserved += len(encoded) + extra
		}
		s.mu.Unlock()
		if len(result.Rows) >= v.opts.MaxRows || !space {
			result.Truncated = true
			break
		}
		result.Rows = append(result.Rows, raw)
		result.original = append(result.original, original)
		result.masked = append(result.masked, masked)
		result.copyLossy = append(result.copyLossy, copyLossy)
		result.rowRefs = append(result.rowRefs, newID("row_"))
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	result.TotalRows = len(result.Rows)
	result.memoryBytes = reserved
	complete = true
	return result, nil
}
func (v *Server) resultRows(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil {
		s.mu.Unlock()
		fail(w, 410, "session_closed", "Session is closed")
		return
	}
	result := s.results[r.PathValue("rid")]
	if result == nil {
		s.mu.Unlock()
		fail(w, 404, "not_found", "Result not found")
		return
	}
	page := *result
	offset := positiveInt(r.URL.Query().Get("offset"), 0, len(result.Rows))
	limit := positiveInt(r.URL.Query().Get("limit"), 200, 1000)
	if limit == 0 {
		limit = 200
	}
	end := min(offset+limit, len(result.Rows))
	page.Rows = append([][]any{}, result.Rows[offset:end]...)
	page.Offset = offset
	if end < len(result.Rows) {
		page.NextOffset = &end
	}
	s.mu.Unlock()
	writeJSON(w, 200, page)
}
func (v *Server) journal(s *session, e *execution, phase string) error {
	dir := v.opts.StateDir
	if dir == "" {
		dir = "data/go"
	}
	dir = filepath.Join(dir, "executions")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	entry := map[string]any{"session_id": s.id, "core_session_id": s.grant.ID, "execution": e.snapshot(), "sql": e.sql, "phase": phase, "recovery": "An unfinished intent has unknown outcome; never replay SQL"}
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".intent-")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(name, filepath.Join(dir, e.view.ID+".json")); err != nil {
		return err
	}
	directory, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
