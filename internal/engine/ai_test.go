package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/sqlservice"
)

func aiFixture(t *testing.T) (*Server, *session, aiEditorContext) {
	v := New(&executionProvider{}, Options{})
	s := seedEngineSession(v, "token")
	s.grant.Config.Dialect = "postgresql"
	s.grant.Config.Database = "app"
	ctx, cancel := context.WithCancel(s.ctx)
	s.consoles["con_ai"] = &console{id: "con_ai", ctx: ctx, cancel: cancel, context: sqlservice.Context{Database: "app", Schema: "public"}}
	// This test has no physical database connection: AI drafting must not execute.
	t.Cleanup(func() { cancel(); s.mu.Lock(); delete(s.consoles, "con_ai"); s.mu.Unlock(); v.Close() })
	return v, s, aiEditorContext{Dialect: "postgresql", Database: "app", Schema: "public", ConsoleID: "con_ai", NodeKey: "con_ai", PaneID: "pane", TabID: "tab", WorkspaceTabID: "tab", WorkspaceTabKind: "query", CurrentContext: `{"database":"app","schema":"public"}`, Revision: 7, DocumentSQL: "SELECT 1", ProposalTarget: "document"}
}
func aiTestBody(s *session, e aiEditorContext, id, name string, args any) string {
	body, _ := json.Marshal(map[string]any{"type": "mcp.request", "version": 1, "resource_session_id": s.id, "data": map[string]any{"jsonrpc": "2.0", "id": id, "method": "tools/call", "params": map[string]any{"name": name, "arguments": args, "_meta": map[string]any{"com.jumpserver/agent": map[string]any{"resource_session_id": s.id, "revision": 1, "tool_call_id": "tool-1"}, "com.jumpserver/sqlContext": e}}}})
	return string(body)
}
func aiResponse(t *testing.T, s *session, id string) map[string]any {
	t.Helper()
	until := time.After(2 * time.Second)
	for {
		s.mu.Lock()
		events := append([]Event(nil), s.history...)
		s.mu.Unlock()
		for _, e := range events {
			if e.Type != "ai.message" {
				continue
			}
			b, _ := json.Marshal(e.Data)
			var frame struct {
				Type string         `json:"type"`
				Data map[string]any `json:"data"`
			}
			_ = json.Unmarshal(b, &frame)
			if frame.Type == "mcp.response" && frame.Data["id"] == id {
				return frame.Data
			}
		}
		select {
		case <-until:
			t.Fatal("AI response not delivered")
			return nil
		case <-time.After(time.Millisecond):
		}
	}
}
func TestAIBridgeManifestDraftAndScopeBinding(t *testing.T) {
	v, s, e := aiFixture(t)
	h := v.Handler()
	w := httptest.NewRecorder()
	h.ServeHTTP(w, engineRequest("GET", "/chen/api/v1/sessions/"+s.id+"/ai/manifest", "", "token"))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"profile":"sql"`) || !strings.Contains(w.Body.String(), `"openWorldHint":true`) {
		t.Fatalf("manifest unavailable: %d", w.Code)
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions/"+s.id+"/ai/messages", aiTestBody(s, e, "proposal", "propose_sql", map[string]any{"sql": "SELECT 2", "explanation": "Read a synthetic value"}), "token"))
	if w.Code != 204 {
		t.Fatalf("proposal submit %d %s", w.Code, w.Body.String())
	}
	response := aiResponse(t, s, "proposal")
	result := response["result"].(map[string]any)["structuredContent"].(map[string]any)
	proposal := result["proposal"].(map[string]any)
	base := proposal["base"].(map[string]any)
	if base["revision"] != float64(7) || base["nodeKey"] != "con_ai" || proposal["originalSql"] != "SELECT 1" {
		t.Fatal("immutable proposal binding lost")
	}
	for _, mutate := range []func(*aiEditorContext){func(e *aiEditorContext) { e.Database = "other" }, func(e *aiEditorContext) { e.NodeKey = "other" }, func(e *aiEditorContext) { e.Revision = -1 }, func(e *aiEditorContext) { e.SelectedSQL = "not selected" }} {
		bad := e
		mutate(&bad)
		w = httptest.NewRecorder()
		h.ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions/"+s.id+"/ai/messages", aiTestBody(s, bad, "forged", "read_sql_context", map[string]any{}), "token"))
		if w.Code != 400 {
			t.Fatal("forged context accepted")
		}
	}
}
func TestAIDraftsRejectWritesAndSelectionDoesNotDiscloseWholeDocument(t *testing.T) {
	v, s, e := aiFixture(t)
	r := engineRequest("POST", "/ai", "", "token")
	for _, sql := range []string{"UPDATE accounts SET name='x'", "DELETE FROM accounts", "CREATE TABLE x(id int)"} {
		args, _ := json.Marshal(map[string]any{"sql": sql, "explanation": "bad"})
		if _, err := v.runAITool(context.Background(), r, s, e, "propose_sql", args); err == nil {
			t.Fatal("write proposal accepted")
		}
	}
	e.DocumentSQL = "SELECT '😀'; SELECT 2"
	e.SelectionFrom = 13
	e.SelectionTo = 21
	e.SelectedSQL = "SELECT 2"
	e.ProposalTarget = "selection"
	raw, _ := json.Marshal(e)
	verified, err := resolveAIEditor(s, raw)
	if err != nil {
		t.Fatal(err)
	}
	result, err := v.runAITool(context.Background(), r, s, verified, "read_sql_context", []byte("{}"))
	if err != nil || result.(aiEditorContext).DocumentSQL != "" || result.(aiEditorContext).SelectedSQL != "SELECT 2" {
		t.Fatal("selection leaked whole document")
	}
	e.SelectionFrom = 9
	e.SelectionTo = 10
	raw, _ = json.Marshal(e)
	if _, err := resolveAIEditor(s, raw); err == nil {
		t.Fatal("split UTF16 surrogate accepted")
	}
}
func TestAICancellationReleasesBoundedCatalogTask(t *testing.T) {
	v, s, e := aiFixture(t)
	started := make(chan struct{}, 1)
	state := &executionDriverState{query: func(ctx context.Context, q string) (driver.Rows, error) {
		select {
		case started <- struct{}{}:
		default:
		}
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	db := sql.OpenDB(executionConnector{state})
	s.catalog = &catalogResource{db: db, slots: make(chan struct{}, 2)}
	h := v.Handler()
	path := "/chen/api/v1/sessions/" + s.id + "/ai/messages"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, engineRequest("POST", path, aiTestBody(s, e, "slow", "search_schema", map[string]any{"query": "order"}), "token"))
	if w.Code != 204 {
		t.Fatal(w.Code)
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("catalog tool did not start")
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, engineRequest("POST", path, aiTestBody(s, e, "slow", "search_schema", map[string]any{"query": "order"}), "token"))
	if w.Code != 409 {
		t.Fatal("duplicate active request accepted")
	}
	cancel, _ := json.Marshal(map[string]any{"type": "mcp.cancel", "version": 1, "resource_session_id": s.id, "data": map[string]any{"jsonrpc": "2.0", "method": "notifications/cancelled", "params": map[string]any{"requestId": "slow"}}})
	w = httptest.NewRecorder()
	h.ServeHTTP(w, engineRequest("POST", path, string(cancel), "token"))
	if w.Code != 204 {
		t.Fatal(w.Code)
	}
	response := aiResponse(t, s, "slow")
	if response["result"].(map[string]any)["isError"] != true {
		t.Fatal("canceled tool returned metadata")
	}
	s.workers.Wait()
	if db.Stats().InUse != 0 {
		t.Fatal("canceled catalog leaked its connection")
	}
}
