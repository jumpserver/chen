package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

const planMaxBytes = 1024 * 1024
const planMaxNodes = 2048
const planMaxDepth = 64

type PlanIdentity struct {
	RequestID    string `json:"request_id"`
	Mode         string `json:"mode"`
	Dialect      string `json:"dialect"`
	Database     string `json:"database"`
	Schema       string `json:"schema"`
	GeneratedSQL string `json:"generated_sql,omitempty"`
}
type PlanNode struct {
	ID               string  `json:"id"`
	NativeID         *string `json:"nativeId"`
	NodeType         string  `json:"nodeType"`
	NativeOperator   string  `json:"nativeOperator"`
	LogicalOperator  *string `json:"logicalOperator"`
	PhysicalOperator *string `json:"physicalOperator"`
	Detail           *string `json:"detail"`
	Table            *string `json:"table"`
	Relation         *string `json:"relation"`
	// Decimal strings preserve all server digits across the JSON/JavaScript boundary.
	Rows        *string           `json:"rows"`
	Cost        *string           `json:"cost"`
	StartupCost *string           `json:"startupCost"`
	RowsMeaning *string           `json:"rowsMeaning"`
	CostMeaning *string           `json:"costMeaning"`
	Predicates  map[string]string `json:"predicates"`
	Attributes  map[string]string `json:"attributes"`
	Children    []*PlanNode       `json:"children"`
}
type PlanCapabilities struct {
	Supported            bool   `json:"supported"`
	Structured           bool   `json:"structured"`
	Hierarchy            bool   `json:"hierarchy"`
	Raw                  bool   `json:"raw"`
	Cost                 bool   `json:"cost"`
	EstimatedRows        bool   `json:"estimatedRows"`
	Predicates           bool   `json:"predicates"`
	TwoStep              bool   `json:"twoStep"`
	RequiresSessionState bool   `json:"requiresSessionState"`
	RequiresPlanTable    bool   `json:"requiresPlanTable"`
	TransactionPolicy    string `json:"transactionPolicy"`
}
type PlanDiagnostic struct {
	Code       string  `json:"code"`
	Message    string  `json:"message"`
	SQLState   *string `json:"sqlState"`
	VendorCode *string `json:"vendorCode"`
}
type PlanEffects struct {
	SessionState          string `json:"sessionState"`
	TransactionState      string `json:"transactionState"`
	AuxiliaryStorage      string `json:"auxiliaryStorage"`
	ConnectionDisposition string `json:"connectionDisposition"`
}
type ExecutionPlan struct {
	ExecutionID      string           `json:"executionId"`
	ConsoleID        string           `json:"consoleId"`
	RequestID        string           `json:"requestId"`
	Context          PlanIdentity     `json:"context"`
	Database         string           `json:"database"`
	ServerVersion    *string          `json:"serverVersion"`
	Mode             string           `json:"mode"`
	Status           string           `json:"status"`
	SQL              string           `json:"sql"`
	Roots            []*PlanNode      `json:"roots"`
	RawText          string           `json:"rawText"`
	RawFormat        string           `json:"rawFormat"`
	RawFormatVersion *string          `json:"rawFormatVersion"`
	RawTruncated     bool             `json:"rawTruncated"`
	Capabilities     PlanCapabilities `json:"capabilities"`
	Prerequisites    []PlanDiagnostic `json:"prerequisites"`
	Effects          PlanEffects      `json:"effects"`
	Error            *PlanDiagnostic  `json:"error"`
	Warnings         []PlanDiagnostic `json:"warnings"`
	Limits           map[string]int   `json:"limits"`
}

func emptyPlan(dialect string) *ExecutionPlan {
	supported := false
	switch dialect {
	case "postgresql", "mysql", "mariadb", "oceanbase-mysql", "clickhouse":
		supported = true
	}
	p := &ExecutionPlan{Database: dialect, Mode: "ESTIMATED", Status: "RAW_ONLY", Roots: []*PlanNode{}, Prerequisites: []PlanDiagnostic{}, Warnings: []PlanDiagnostic{},
		Effects:      PlanEffects{"UNKNOWN", "UNKNOWN", "NONE", "UNKNOWN"},
		Capabilities: PlanCapabilities{Supported: supported, TransactionPolicy: "STATEMENT_ONLY"},
		Limits:       map[string]int{"maxBytes": planMaxBytes, "maxNodes": planMaxNodes, "maxDepth": planMaxDepth}}
	if dialect == "postgresql" {
		p.Capabilities.TransactionPolicy = "SAVEPOINT_IF_ACTIVE"
	}
	return p
}
func (p *ExecutionPlan) warn(code, message string) {
	p.Warnings = append(p.Warnings, PlanDiagnostic{Code: code, Message: message})
}

func (v *Server) executionPlan(w http.ResponseWriter, r *http.Request) {
	s := v.auth(w, r, false)
	if s == nil {
		return
	}
	s.mu.Lock()
	e := s.executions[r.PathValue("eid")]
	closed := s.closed || s.ctx.Err() != nil
	s.mu.Unlock()
	if closed {
		fail(w, 410, "session_closed", "Session is closed")
		return
	}
	if e == nil || e.planMode == "" {
		fail(w, 404, "plan_not_found", "Execution has no server-admitted plan intent")
		return
	}
	e.mu.RLock()
	p := emptyPlan(e.view.Plan.Dialect)
	if e.planResult != nil {
		copy := *e.planResult
		p = &copy
	}
	p.ExecutionID, p.ConsoleID, p.RequestID, p.Context, p.SQL = e.view.ID, e.view.ConsoleID, e.view.Plan.RequestID, *e.view.Plan, e.sql
	p.Context.GeneratedSQL = e.planSQL
	switch e.view.Status {
	case "queued", "waiting_review", "running", "cancel_requested":
		p.Status = strings.ToUpper(e.view.Status)
	case "canceled":
		p.Status = "CANCELLED"
	case "unknown":
		p.Status = "CONNECTION_INVALIDATED"
	case "failed":
		p.Status = "ERROR"
		if e.view.Error != nil {
			switch e.view.Error.Code {
			case "explain_not_supported":
				p.Status = "UNSUPPORTED_DATABASE"
			case "explain_requires_single_select", "explain_unsafe_select":
				p.Status = "UNSUPPORTED_STATEMENT"
			case "plan_transaction_failed", "plan_transaction_unknown":
				p.Status = "PREREQUISITES_UNMET"
			case "connection_lost", "plan_connection_lost":
				p.Status = "CONNECTION_INVALIDATED"
			}
		}
	}
	if e.view.Error != nil {
		p.Error = &PlanDiagnostic{Code: e.view.Error.Code, Message: e.view.Error.Message}
	}
	e.mu.RUnlock()
	if p.Status == "CONNECTION_INVALIDATED" {
		p.Effects.ConnectionDisposition = "DISCARD"
	}
	writeJSON(w, 200, p)
}

func planAPIError(err error) *APIError {
	var api *APIError
	if errors.As(err, &api) {
		return api
	}
	return safeDatabaseError(err)
}

// Run on the already locked console. PostgreSQL errors are isolated with a
// savepoint only when a user transaction is active; no BEGIN/COMMIT is injected.
func (v *Server) runEstimatedPlan(s *session, c *console, e *execution, command string) (err error) {
	var boundary *editBoundary
	if s.grant.Config.Dialect == "postgresql" {
		state, probeErr := editTransactionState(e.ctx, c, "postgresql")
		if probeErr != nil {
			return probeErr
		}
		if state == editTxFailed {
			return &APIError{"plan_transaction_failed", "Roll back the failed transaction before requesting a plan"}
		}
		if state != editTxIdle && state != editTxActive {
			return &APIError{"plan_transaction_unknown", "Cannot verify the console transaction state"}
		}
		if state == editTxActive {
			boundary = &editBoundary{conn: c.conn, dialect: "postgresql", savepoint: "chen_plan_" + newID("")[:16]}
			if _, err = c.conn.ExecContext(e.ctx, "SAVEPOINT "+boundary.savepoint); err != nil {
				return err
			}
		}
	}
	var p *ExecutionPlan
	reserved := 0
	defer func() {
		if boundary != nil {
			// A concurrent cancel must finish its control command before cleanup SQL.
			if e.cancelRequested.Load() {
				<-e.cancelDone
			}
			ctx, stop := context.WithTimeout(context.Background(), 3*time.Second)
			defer stop()
			var cleanupErr error
			if err != nil {
				cleanupErr = boundary.rollback(ctx)
			} else {
				cleanupErr = boundary.finish(ctx)
			}
			if cleanupErr != nil {
				c.lost.Store(true)
				s.emit("console.updated", ConsoleView{c.id, c.context.Database, c.context.Schema, "lost"})
				err = &APIError{"plan_connection_lost", "Plan savepoint cleanup failed; transaction state is unknown. Reconnect before continuing"}
			} else if p != nil {
				p.Effects.TransactionState = "PARTICIPATED"
			}
		}
		if p != nil {
			v.reservePlan(s, p, reserved)
			e.mu.Lock()
			e.planResult = p
			e.mu.Unlock()
		}
	}()
	rows, err := c.conn.QueryContext(executionContext(e.ctx, s.grant.Config.Dialect, e.view.ID), command)
	if err != nil {
		return err
	}
	defer rows.Close()
	result, err := v.readRows(s, rows)
	if err != nil {
		return err
	}
	reserved = result.memoryBytes
	// readRows has applied the existing masking and per-session row/byte limits.
	// Retain only the masked plan, never the result's original/edit buffers.
	p = normalizePlan(s.grant.Config.Dialect, result)
	if rows.NextResultSet() {
		p.Status = "RAW_ONLY"
		p.Roots = []*PlanNode{}
		p.Capabilities.Structured = false
		p.Capabilities.Hierarchy = false
		p.warn("plan_extra_results", "Additional plan result sets are not supported")
	}
	if err = rows.Err(); err != nil {
		return err
	}
	if err = rows.Close(); err != nil {
		return err
	}
	return nil
}

func (v *Server) reservePlan(s *session, p *ExecutionPlan, reserved int) {
	// As for ordinary results, the session byte quota bounds variable result data.
	// Fixed execution metadata is separately bounded by the 256-execution limit.
	size := func() int { nodes, _ := json.Marshal(p.Roots); return len(p.RawText) + max(0, len(nodes)-2) }
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resultBytes -= reserved
	available := max(0, v.opts.MaxResultBytes-s.resultBytes)
	if size() > available {
		p.Roots = []*PlanNode{}
		p.Capabilities.Structured = false
		p.Capabilities.Hierarchy = false
		p.RawText = planPrefix(p.RawText, available)
		p.RawTruncated = true
		p.Status = "RAW_ONLY"
		p.warn("plan_memory_limit", "Plan output reached the session memory limit")
	}
	p.Capabilities.Raw = p.RawText != ""
	s.resultBytes += size()
}

func planPrefix(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	s = s[:max(0, limit)]
	for !utf8.ValidString(s) && len(s) > 0 {
		s = s[:len(s)-1]
	}
	return s
}
func normalizePlan(dialect string, r *Result) *ExecutionPlan {
	p := emptyPlan(dialect)
	p.RawFormat = "TABLE"
	raw, _ := json.Marshal(struct {
		Columns []Column `json:"columns"`
		Rows    [][]any  `json:"rows"`
	}{r.Columns, r.Rows})
	p.RawText = string(raw)
	if dialect == "postgresql" && len(r.Rows) == 1 && len(r.Rows[0]) == 1 {
		p.RawFormat = "JSON"
		p.RawText = planText(r.Rows[0][0])
	}
	if dialect == "clickhouse" {
		p.RawFormat = "TEXT"
		lines := []string{}
		for _, row := range r.Rows {
			cells := []string{}
			for _, cell := range row {
				cells = append(cells, planText(cell))
			}
			lines = append(lines, strings.Join(cells, "\t"))
		}
		p.RawText = strings.Join(lines, "\n")
	}
	p.RawTruncated = r.Truncated || len(p.RawText) > planMaxBytes
	p.RawText = planPrefix(p.RawText, planMaxBytes)
	p.Capabilities.Raw = p.RawText != ""
	masked := false
	for _, row := range r.masked {
		for _, m := range row {
			masked = masked || m
		}
	}
	switch {
	case p.RawTruncated:
		p.warn("plan_truncated", "Raw plan was truncated by row or byte limits; no complete structure is claimed")
	case masked:
		p.warn("plan_masked", "The data masking policy changed the plan; only masked raw output is available")
	case len(r.Rows) == 0:
		p.warn("plan_empty", "The database returned no plan rows")
	case dialect == "postgresql":
		roots, err := parsePostgresPlan(p.RawText)
		if err != nil {
			p.warn("plan_parse_failed", err.Error())
		} else {
			p.Roots = roots
			p.Status = "SUCCESS"
			p.Capabilities.Structured = true
			p.Capabilities.Hierarchy = true
			p.Capabilities.Cost = true
			p.Capabilities.EstimatedRows = true
			p.Capabilities.Predicates = true
		}
	case dialect == "mysql":
		roots, err := parseMySQLPlan(r)
		if err != nil {
			p.warn("plan_parse_failed", err.Error())
		} else {
			p.Roots = roots
			p.Status = "SUCCESS"
			p.Capabilities.Structured = true
			p.Capabilities.EstimatedRows = true
			p.RawFormatVersion = planString("mysql-classic")
			p.warn("plan_flat_summary", "Classic EXPLAIN rows are independent facts; parent/child hierarchy and costs are unavailable")
		}
	default:
		p.warn("plan_raw_dialect", "This dialect currently supports estimated raw output only; structured normalization has not been verified")
	}
	return p
}
func planString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
func planText(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	if n, ok := v.(json.Number); ok {
		return string(n)
	}
	b, _ := json.Marshal(v)
	return string(b)
}
func planNumber(v any) *string {
	s := planText(v)
	if s == "" {
		return nil
	}
	// Decode only a JSON number, retaining the exact decimal/exponent spelling.
	d := json.NewDecoder(strings.NewReader(s))
	d.UseNumber()
	var value any
	if d.Decode(&value) != nil {
		return nil
	}
	n, ok := value.(json.Number)
	if !ok {
		return nil
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return nil
	}
	return planString(string(n))
}
func newPlanNode(id, operator string) *PlanNode {
	if operator == "" {
		operator = "Unknown"
	}
	return &PlanNode{ID: id, NativeOperator: operator, NodeType: "OTHER", Predicates: map[string]string{}, Attributes: map[string]string{}, Children: []*PlanNode{}}
}
func parsePostgresPlan(raw string) ([]*PlanNode, error) {
	fail := errors.New("Unrecognized or incomplete PostgreSQL plan JSON; inspect the raw output")
	if len(raw) > planMaxBytes {
		return nil, errors.New("Plan JSON exceeds the byte limit")
	}
	// Bound all JSON nesting, including unknown attributes, before allocating maps.
	d := json.NewDecoder(strings.NewReader(raw))
	d.UseNumber()
	depth := 0
	for {
		token, err := d.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fail
		}
		if delim, ok := token.(json.Delim); ok {
			if delim == '[' || delim == '{' {
				depth++
				if depth > planMaxDepth*3 {
					return nil, errors.New("Plan JSON exceeds the nesting limit")
				}
			} else {
				depth--
			}
		}
	}
	d = json.NewDecoder(strings.NewReader(raw))
	d.UseNumber()
	var value any
	if d.Decode(&value) != nil {
		return nil, fail
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return nil, fail
	}
	items, ok := value.([]any)
	if !ok {
		items = []any{value}
	}
	roots := []*PlanNode{}
	count := 0
	var convert func(any, int) (*PlanNode, error)
	convert = func(value any, depth int) (*PlanNode, error) {
		count++
		if count > planMaxNodes || depth > planMaxDepth {
			return nil, errors.New("Plan exceeds the node or depth limit")
		}
		m, ok := value.(map[string]any)
		if !ok || len(m) == 0 {
			return nil, fail
		}
		n := newPlanNode(fmt.Sprintf("n%d", count), planText(m["Node Type"]))
		n.NodeType = pgNodeType(n.NativeOperator)
		n.NativeID = planString(planText(m["Node Id"]))
		n.LogicalOperator = planString(planText(m["Join Type"]))
		n.PhysicalOperator = planString(n.NativeOperator)
		table := planText(m["Relation Name"])
		if table == "" {
			table = planText(m["CTE Name"])
		}
		n.Table = planString(table)
		if schema := planText(m["Schema"]); schema != "" && table != "" {
			table = schema + "." + table
		}
		n.Relation = planString(table)
		n.Rows, n.Cost, n.StartupCost = planNumber(m["Plan Rows"]), planNumber(m["Total Cost"]), planNumber(m["Startup Cost"])
		n.RowsMeaning = planString("estimated output rows per invocation")
		n.CostMeaning = planString("PostgreSQL estimated total subtree cost")
		for key, value := range m {
			switch key {
			case "Plans", "Subplans", "InitPlan":
				// Children are appended below in a stable vendor-defined field order.
			case "Filter", "Index Cond", "Recheck Cond", "Join Filter", "Hash Cond", "Merge Cond", "TID Cond", "Cache Key", "One-Time Filter":
				n.Predicates[key] = planText(value)
			default:
				if value != nil {
					n.Attributes[key] = planText(value)
				}
			}
		}
		for _, key := range []string{"Plans", "Subplans", "InitPlan"} {
			value, present := m[key]
			if !present {
				continue
			}

			children, ok := value.([]any)
			if !ok {
				if _, ok := value.(map[string]any); ok {
					children = []any{value}
				} else {
					return nil, fail
				}
			}
			for _, child := range children {
				node, err := convert(child, depth+1)
				if err != nil {
					return nil, err
				}
				n.Children = append(n.Children, node)
			}
		}
		return n, nil
	}
	for _, item := range items {
		m, ok := item.(map[string]any)
		if !ok {
			return nil, fail
		}
		root, ok := m["Plan"]
		if !ok {
			if _, ok := m["Node Type"]; !ok {
				return nil, fail
			}
			root = m
		}
		node, err := convert(root, 1)
		if err != nil {
			return nil, err
		}
		roots = append(roots, node)
	}
	if len(roots) == 0 {
		return nil, fail
	}
	return roots, nil
}
func pgNodeType(op string) string {
	switch op {
	case "Seq Scan", "CTE Scan", "Function Scan", "Table Function Scan", "Bitmap Heap Scan", "Tid Scan", "Foreign Scan", "WorkTable Scan":
		return "SCAN"
	case "Index Scan", "Bitmap Index Scan":
		return "INDEX_SCAN"
	case "Index Only Scan":
		return "INDEX_ONLY_SCAN"
	case "Hash Join":
		return "HASH_JOIN"
	case "Merge Join":
		return "MERGE_JOIN"
	case "Nested Loop":
		return "NESTED_LOOP"
	case "Sort", "Incremental Sort":
		return "SORT"
	case "Aggregate", "Group":
		return "AGGREGATE"
	case "Limit":
		return "LIMIT"
	case "Hash":
		return "HASH"
	case "Gather", "Gather Merge":
		return "PARALLEL"
	case "Append", "Merge Append", "Recursive Union", "SetOp":
		return "UNION"
	case "Values Scan":
		return "VALUES"
	case "Materialize", "Memoize":
		return "MATERIALIZE"
	case "Result":
		return "PROJECTION"
	case "Subquery Scan":
		return "SUBQUERY"
	default:
		return "OTHER"
	}
}
func parseMySQLPlan(r *Result) ([]*PlanNode, error) {
	columns := map[string]int{}
	for i, c := range r.Columns {
		columns[strings.ToLower(c.Name)] = i
	}
	for _, key := range []string{"id", "select_type", "table", "type"} {
		if _, ok := columns[key]; !ok {
			return nil, errors.New("Unrecognized MySQL classic columns; inspect the raw output")
		}
	}
	if len(r.Rows) > planMaxNodes {
		return nil, errors.New("Plan exceeds the node limit")
	}
	roots := []*PlanNode{}
	for i, row := range r.Rows {
		if len(row) != len(r.Columns) {
			return nil, errors.New("Incomplete MySQL classic row")
		}
		value := func(key string) any {
			if index, ok := columns[key]; ok {
				return row[index]
			}
			return nil
		}
		n := newPlanNode(fmt.Sprintf("n%d", i+1), planText(value("type")))
		switch strings.ToLower(n.NativeOperator) {
		case "all":
			n.NodeType = "SCAN"
		case "index", "range", "ref", "eq_ref", "const", "system", "index_merge", "ref_or_null":
			n.NodeType = "INDEX_SCAN"
		}
		n.NativeID = planString(planText(value("id")))
		n.Table = planString(planText(value("table")))
		n.Relation = n.Table
		n.Rows = planNumber(value("rows"))
		n.RowsMeaning = planString("MySQL estimated rows examined per scan")
		n.Detail = planString(planText(value("extra")))
		for key, index := range columns {
			if row[index] != nil {
				n.Attributes[key] = planText(row[index])
			}
		}
		roots = append(roots, n)
	}
	return roots, nil
}
