package engine

import "net/http"

const aiRevision = 1
const aiMaxSQL = 128 << 10

type aiFrame struct {
	Type              string `json:"type"`
	Version           int    `json:"version"`
	ResourceSessionID string `json:"resource_session_id"`
	Data              any    `json:"data"`
}

func aiManifest(s *session) aiFrame {
	str := func(n int) map[string]any { return map[string]any{"type": "string", "maxLength": n} }
	requiredSQL := str(aiMaxSQL)
	requiredSQL["minLength"] = 1
	tool := func(name, title, description string, properties map[string]any, required []string, open bool) map[string]any {
		schema := map[string]any{"type": "object", "additionalProperties": false, "properties": properties}
		if len(required) > 0 {
			schema["required"] = required
		}
		return map[string]any{"name": name, "title": title, "description": description, "inputSchema": schema, "annotations": map[string]any{"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": open}}
	}
	read := tool("read_sql_context", "Read SQL editor context", "Read Chen-verified editor selection and connection scope. Never reads business rows.", map[string]any{}, nil, false)
	search := tool("search_schema", "Find related tables", "Find bounded table candidates by table and column names/comments in the verified schema. Never reads business rows.", map[string]any{"query": str(256), "schema": str(256), "limit": map[string]any{"type": "integer", "minimum": 1, "maximum": 10}}, []string{"query"}, false)
	inspect := tool("inspect_schema", "Inspect database schema", "Inspect bounded table, column, key and index metadata. Use exact tables or a literal query; * performs bounded discovery. Never reads business rows.", map[string]any{"query": str(256), "schema": str(256), "tables": map[string]any{"type": "array", "minItems": 1, "maxItems": 8, "items": str(256)}}, nil, true)
	inspect["inputSchema"].(map[string]any)["anyOf"] = []any{map[string]any{"required": []string{"query"}}, map[string]any{"required": []string{"tables"}}}
	validate := tool("validate_sql", "Validate SQL draft", "Parse SQL locally with the configured dialect. valid=false means Chen cannot parse this draft; valid=true is not a safety or permission guarantee. Never executes SQL.", map[string]any{"sql": requiredSQL}, []string{"sql"}, false)
	proposal := tool("propose_sql", "Propose SQL draft", "Prepare a read-only SQL draft for explicit user review. Never applies or executes SQL.", map[string]any{"sql": requiredSQL, "explanation": str(4096)}, []string{"sql", "explanation"}, false)
	proposal["_meta"] = map[string]any{"com.jumpserver/finalResult": true}
	data := map[string]any{"version": 1, "resource_session_id": s.id, "profile": "sql", "revision": aiRevision, "context": map[string]any{"session_kind": "sql_editor", "interaction_mode": "draft_only", "command_language": "sql", "dialect": s.grant.Config.Dialect, "protocol": s.grant.Config.Dialect, "asset_id": s.grant.AssetID, "asset_name": s.grant.AssetName, "database": s.grant.Config.Database}, "tools": []any{read, search, inspect, validate, proposal}}
	return aiFrame{"mcp.manifest", 1, s.id, data}
}
func (v *Server) aiManifestHTTP(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	if v.opts.SingleMode {
		fail(w, 404, "ai_unavailable", "AI requires a managed session")
		return
	}
	writeJSON(w, 200, aiManifest(s))
}
