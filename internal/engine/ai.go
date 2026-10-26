package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf16"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type aiTask struct{ cancel context.CancelFunc }
type aiRPC struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  struct {
		Name      string                     `json:"name"`
		Arguments json.RawMessage            `json:"arguments"`
		Meta      map[string]json.RawMessage `json:"_meta"`
		RequestID json.RawMessage            `json:"requestId"`
		Reason    string                     `json:"reason"`
	} `json:"params"`
}
type aiEditorContext struct {
	Dialect          string `json:"dialect"`
	Database         string `json:"database"`
	Schema           string `json:"schema"`
	NodeKey          string `json:"nodeKey"`
	ConsoleID        string `json:"consoleId"`
	PaneID           string `json:"paneId"`
	TabID            string `json:"tabId"`
	WorkspaceTabID   string `json:"workspaceTabId"`
	WorkspaceTabKind string `json:"workspaceTabKind"`
	CurrentContext   string `json:"currentContext"`
	Revision         int    `json:"revision"`
	SelectionFrom    int    `json:"selectionFrom"`
	SelectionTo      int    `json:"selectionTo"`
	SelectedSQL      string `json:"selectedSql"`
	DocumentSQL      string `json:"documentSql"`
	ProposalTarget   string `json:"proposalTarget"`
	LastError        any    `json:"lastError,omitempty"`
}

func aiRequestID(raw json.RawMessage) (string, error) {
	var id string
	if json.Unmarshal(raw, &id) != nil || id == "" || len(id) > 128 {
		return "", errors.New("A string request id of at most 128 bytes is required")
	}
	return id, nil
}
func (v *Server) aiMessages(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	if v.opts.SingleMode {
		fail(w, 404, "ai_unavailable", "AI requires a managed session")
		return
	}
	var frame struct {
		Type              string          `json:"type"`
		Version           int             `json:"version"`
		ResourceSessionID string          `json:"resource_session_id"`
		Data              json.RawMessage `json:"data"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 320<<10)
	if !decode(w, r, &frame) {
		return
	}
	if frame.Version != 1 || frame.ResourceSessionID != s.id || (frame.Type != "mcp.request" && frame.Type != "mcp.cancel") {
		fail(w, 400, "invalid_ai_frame", "Invalid AI session binding")
		return
	}
	raw := frame.Data
	var encoded string
	if len(raw) > 0 && raw[0] == '"' {
		if json.Unmarshal(raw, &encoded) != nil {
			fail(w, 400, "invalid_ai_frame", "Invalid AI frame")
			return
		}
		raw = []byte(encoded)
	}
	var rpc aiRPC
	if json.Unmarshal(raw, &rpc) != nil || rpc.JSONRPC != "2.0" {
		fail(w, 400, "invalid_ai_frame", "Invalid JSON-RPC request")
		return
	}
	if frame.Type == "mcp.cancel" {
		if rpc.Method != "notifications/cancelled" {
			fail(w, 400, "invalid_ai_frame", "Invalid AI cancellation")
			return
		}
		id, err := aiRequestID(rpc.Params.RequestID)

		if err != nil {
			fail(w, 400, "invalid_ai_frame", err.Error())
			return
		}
		s.mu.Lock()
		task := s.aiTasks[id]
		s.mu.Unlock()
		if task != nil {
			task.cancel()
		}
		s.emit("ai.message", aiFrame{"mcp.cancel_result", 1, s.id, map[string]any{"jsonrpc": "2.0", "id": id, "version": 1, "resource_session_id": s.id, "result": map[string]bool{"cancelled": task != nil}}})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	id, err := aiRequestID(rpc.ID)
	if err != nil || rpc.Method != "tools/call" {
		fail(w, 400, "invalid_ai_frame", "Only tools/call with a string request id is supported")
		return
	}
	var binding struct {
		ResourceSessionID string `json:"resource_session_id"`
		Revision          int    `json:"revision"`
		ToolCallID        string `json:"tool_call_id"`
	}
	if json.Unmarshal(rpc.Params.Meta["com.jumpserver/agent"], &binding) != nil || binding.ResourceSessionID != s.id || binding.Revision != aiRevision || binding.ToolCallID == "" || len(binding.ToolCallID) > 256 {
		fail(w, 400, "invalid_ai_binding", "Invalid AI resource binding")
		return
	}
	editor, err := resolveAIEditor(s, rpc.Params.Meta["com.jumpserver/sqlContext"])
	if err != nil {
		fail(w, 400, "invalid_ai_context", err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(s.ctx, 15*time.Second)
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil {
		s.mu.Unlock()
		cancel()
		fail(w, 410, "session_closed", "Session is closed")
		return
	}
	if s.aiTasks == nil {
		s.aiTasks = map[string]*aiTask{}
	}
	if _, exists := s.aiTasks[id]; exists {
		s.mu.Unlock()
		cancel()
		fail(w, 409, "duplicate_ai_request", "AI request id is already active")
		return
	}
	if len(s.aiTasks) >= 2 {
		s.mu.Unlock()
		cancel()
		fail(w, 429, "ai_busy", "At most two AI tools can run per session")
		return
	}
	s.aiTasks[id] = &aiTask{cancel: cancel}
	s.workers.Add(1)
	s.mu.Unlock()
	request := r.Clone(ctx)
	go func() {
		defer s.workers.Done()
		defer cancel()
		defer func() { s.mu.Lock(); delete(s.aiTasks, id); s.mu.Unlock() }()
		now := time.Now()
		auditErr := v.provider.Record(ctx, s.grant, jumpserver.AuditRecord{ID: newID("ai_"), RiskClassified: true, SQL: "/* Chen AI draft tool */", Output: "tool=" + rpc.Params.Name, StartedAt: now, FinishedAt: now})
		var result any
		var err error
		if auditErr != nil {
			err = errors.New("AI tool access could not be audited")
		} else {
			result, err = v.runAITool(ctx, request, s, editor, rpc.Params.Name, rpc.Params.Arguments)
		}
		response := map[string]any{"jsonrpc": "2.0", "id": id, "version": 1, "resource_session_id": s.id}
		if ctx.Err() != nil {
			status, code := "cancelled", "tool_cancelled"
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				status, code = "timeout", "tool_timeout"
			}
			response["result"] = map[string]any{"isError": true, "content": []any{map[string]any{"type": "text", "text": "AI tool " + status}}, "_meta": map[string]any{"com.jumpserver/agent": map[string]any{"status": status, "code": code}}}
		} else if err != nil {
			response["error"] = map[string]any{"code": -32602, "message": err.Error()}
		} else {
			b, e := json.Marshal(result)
			if e != nil || len(b) > 128<<10 {
				response["error"] = map[string]any{"code": -32603, "message": "Tool result exceeds the bounded response size; inspect fewer tables"}
			} else {
				response["result"] = map[string]any{"content": []any{map[string]any{"type": "text", "text": string(b)}}, "structuredContent": result}
			}
		}
		// A locked/revoked session must not disclose metadata, SQL or proposals after
		// the tool began. The frontend separately enforces ready state when applying.
		if s.ctx.Err() == nil && s.grant.Check() == nil {
			s.emit("ai.message", aiFrame{"mcp.response", 1, s.id, response})
		}
	}()
	w.WriteHeader(http.StatusNoContent)
}
func resolveAIEditor(s *session, raw json.RawMessage) (aiEditorContext, error) {
	var e aiEditorContext
	if len(raw) == 0 || len(raw) > 288<<10 || json.Unmarshal(raw, &e) != nil {
		return e, errors.New("Invalid SQL editor context")
	}
	for _, value := range []string{e.NodeKey, e.ConsoleID, e.PaneID, e.TabID, e.WorkspaceTabID, e.Database, e.Schema, e.CurrentContext} {
		if len(value) > 1024 || strings.ContainsRune(value, 0) {
			return e, errors.New("Invalid SQL editor identifier")
		}
	}
	s.mu.Lock()
	c := s.consoles[e.ConsoleID]
	s.mu.Unlock()
	if c == nil || c.closed.Load() || c.lost.Load() || e.NodeKey != c.id || e.WorkspaceTabKind != "query" {
		return e, errors.New("An active SQL query console is required")
	}
	if e.Database != c.context.Database || e.Schema != c.context.Schema || e.Dialect != s.grant.Config.Dialect {
		return e, errors.New("SQL editor scope changed; refresh the editor context")
	}
	if e.Revision < 0 || len(e.DocumentSQL) > aiMaxSQL || e.SelectionFrom < 0 || e.SelectionTo < e.SelectionFrom {
		return e, errors.New("Invalid SQL document or selection")
	}
	doc := utf16.Encode([]rune(e.DocumentSQL))
	if e.SelectionTo > len(doc) {
		return e, errors.New("Invalid SQL selection")
	}
	// Do not split a surrogate pair when converting Monaco's UTF-16 offsets.
	for _, p := range []int{e.SelectionFrom, e.SelectionTo} {
		if p > 0 && p < len(doc) && doc[p] >= 0xdc00 && doc[p] <= 0xdfff && doc[p-1] >= 0xd800 && doc[p-1] <= 0xdbff {
			return e, errors.New("Invalid SQL selection boundary")
		}
	}
	selected := string(utf16.Decode(doc[e.SelectionFrom:e.SelectionTo]))
	if e.SelectedSQL != selected {
		return e, errors.New("SQL selection no longer matches the document")
	}
	if e.ProposalTarget == "" {
		e.ProposalTarget = "document"
		if selected != "" {
			e.ProposalTarget = "selection"
		}
	}
	if e.ProposalTarget != "selection" && e.ProposalTarget != "document" && e.ProposalTarget != "new_query" || e.ProposalTarget == "selection" && selected == "" || e.ProposalTarget != "new_query" && e.TabID == "" {
		return e, errors.New("Invalid SQL proposal target")
	}
	e.LastError = nil // Server errors may contain business data; never forward browser text.
	return e, nil
}
func (v *Server) runAITool(ctx context.Context, r *http.Request, s *session, e aiEditorContext, name string, raw json.RawMessage) (any, error) {
	var args struct {
		SQL         string   `json:"sql"`
		Explanation string   `json:"explanation"`
		Query       string   `json:"query"`
		Schema      string   `json:"schema"`
		Tables      []string `json:"tables"`
		Limit       int      `json:"limit"`
	}
	if len(raw) == 0 {
		raw = []byte("{}")
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&args) != nil || decoder.Decode(new(any)) != io.EOF {
		return nil, errors.New("Invalid tool arguments")
	}
	if err := validateAIToolArguments(name, raw); err != nil {
		return nil, err
	}
	if err := s.grant.Check(); err != nil {
		return nil, errors.New("Session is unavailable")
	}
	switch name {
	case "read_sql_context":
		if string(raw) != "{}" {
			var m map[string]any
			_ = json.Unmarshal(raw, &m)
			if len(m) != 0 {
				return nil, errors.New("read_sql_context takes no arguments")
			}
		}
		if e.SelectedSQL != "" {
			e.DocumentSQL = ""
		}
		return e, nil
	case "validate_sql", "propose_sql":
		if strings.TrimSpace(args.SQL) == "" || len(args.SQL) > aiMaxSQL || len(args.Explanation) > 4096 {
			return nil, errors.New("Invalid SQL draft")
		}
		analysis, err := sqlservice.Analyze(sqlservice.Dialect(e.Dialect), args.SQL)
		if err == nil && (len(analysis.Statements) == 0 || len(analysis.Statements) > 100) {
			err = errors.New("Invalid statement count")
		}
		checked := map[string]any{"valid": err == nil, "statementCount": len(analysis.Statements), "statements": analysis.Statements, "dialect": e.Dialect, "execution": "never"}
		if err != nil {
			checked["message"] = "Chen cannot parse this SQL draft in the selected dialect"
		}
		if name == "validate_sql" {
			return checked, nil
		}
		if err != nil {
			return nil, errors.New("SQL draft cannot be parsed")
		}
		for _, st := range analysis.Statements {
			if st.MayWrite || !st.ReturnsRows {
				return nil, errors.New("Only read-only query proposals are supported")
			}
		}
		original := e.DocumentSQL
		if e.ProposalTarget == "selection" {
			original = e.SelectedSQL
		} else if e.ProposalTarget == "new_query" {
			original = ""
		}
		if e.ProposalTarget != "new_query" && strings.TrimSpace(original) == strings.TrimSpace(args.SQL) {
			return nil, errors.New("SQL proposal does not change the editor")
		}
		return map[string]any{"kind": "proposal", "analysis": checked, "proposal": map[string]any{"sql": args.SQL, "originalSql": original, "explanation": args.Explanation, "analysis": checked, "base": map[string]any{"paneId": e.PaneID, "tabId": e.TabID, "workspaceTabId": e.WorkspaceTabID, "workspaceTabKind": e.WorkspaceTabKind, "currentContext": e.CurrentContext, "revision": e.Revision, "selectionFrom": e.SelectionFrom, "selectionTo": e.SelectionTo, "nodeKey": e.NodeKey, "database": e.Database, "schema": e.Schema, "target": e.ProposalTarget}}}, nil
	case "search_schema", "inspect_schema":
		if args.Schema != "" && args.Schema != e.Schema {
			return nil, errors.New("Schema is outside the verified editor scope")
		}
		if len(args.Query) > 256 || len(args.Tables) > 8 || args.Limit < 0 || args.Limit > 10 {
			return nil, errors.New("Invalid schema lookup limits")
		}
		if name == "search_schema" && (strings.TrimSpace(args.Query) == "" || args.Query == "*") {
			return nil, errors.New("Schema search requires a business term")
		}
		if name == "inspect_schema" && strings.TrimSpace(args.Query) == "" && len(args.Tables) == 0 {
			return nil, errors.New("Schema query or exact tables are required")
		}
		for _, table := range args.Tables {
			if table == "" || len(table) > 256 || strings.ContainsRune(table, 0) {
				return nil, errors.New("Invalid table identifier")
			}
		}
		rr := r.Clone(ctx)
		rr.URL = &url.URL{Path: r.URL.Path, RawQuery: url.Values{"schema": {e.Schema}, "limit": {"100"}}.Encode()}
		w := &aiCatalogWriter{header: make(http.Header)}
		req := v.beginCatalog(w, rr, "ai")
		if req == nil {
			return nil, errors.New("Authorized catalog is unavailable")
		}
		defer req.done()
		objects, err := sqlservice.ObjectRows(req.ctx, req.conn, sqlservice.Dialect(e.Dialect), sqlservice.CatalogObjectRequest{Database: req.database, Schema: e.Schema, Limit: 100})
		if err != nil {
			return nil, errors.New("Unable to inspect authorized schema")
		}
		result, err := aiSchemaResult(req, e, name, args.Query, args.Tables, args.Limit, objects)
		if err != nil {
			return nil, err
		}
		if v.catalogAudit(req, "succeeded", len(objects)) != nil {
			return nil, errors.New("Metadata audit could not be persisted")
		}
		return result, nil
	default:
		return nil, fmt.Errorf("Unknown SQL tool %q", name)
	}
}

// The catalog lifecycle shares authentication, audit and concurrency limits with
// the tree API; internal failures are mapped to a stable tool error.
type aiCatalogWriter struct {
	header http.Header
	body   bytes.Buffer
	status int
}

func (w *aiCatalogWriter) Header() http.Header         { return w.header }
func (w *aiCatalogWriter) Write(b []byte) (int, error) { return w.body.Write(b) }
func (w *aiCatalogWriter) WriteHeader(status int)      { w.status = status }

func validateAIToolArguments(name string, raw json.RawMessage) error {
	allowed := map[string]bool{}
	switch name {
	case "read_sql_context":
	case "validate_sql":
		allowed["sql"] = true
	case "propose_sql":
		allowed["sql"], allowed["explanation"] = true, true
	case "search_schema":
		allowed["query"], allowed["schema"], allowed["limit"] = true, true, true
	case "inspect_schema":
		allowed["query"], allowed["schema"], allowed["tables"] = true, true, true
	default:
		return errors.New("Unknown SQL tool")
	}
	var args map[string]json.RawMessage
	if json.Unmarshal(raw, &args) != nil || args == nil {
		return errors.New("Tool arguments must be an object")
	}
	for key := range args {
		if !allowed[key] {
			return errors.New("Unexpected tool argument")
		}
	}
	if name == "propose_sql" {
		if _, ok := args["explanation"]; !ok {
			return errors.New("Proposal explanation is required")
		}
	}
	return nil
}
