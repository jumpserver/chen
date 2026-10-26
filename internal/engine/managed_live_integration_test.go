package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// This opt-in acceptance test uses a disposable, locally isolated Core/Wisp
// fixture. It creates its own connection tokens, sessions and review tickets.
// SQL is synthetic SELECT only. Core notifications must use a local mail sink.
// CHEN_MANAGED_FIXTURE=/absolute/private/fixture.json go test -race ./internal/engine -run TestManagedLive -v -count=1
func TestManagedLive(t *testing.T) {
	f := loadManagedFixture(t)
	t.Run("policy_review_audit_controls", func(t *testing.T) {
		m := newManagedLive(t, f, 30*time.Second)
		s, c, coreID := m.session()
		if !s.Session.Permissions.Copy || !s.Session.Permissions.Paste || s.Session.Permissions.Upload || s.Session.Permissions.Download {
			t.Fatal("Core scoped connection/copy/paste permissions were not preserved")
		}
		allowed := m.submit(s, c, "SELECT 1 AS chen_allowed", false)
		m.done(s, allowed.ID, "succeeded", "")
		rejected := m.submit(s, c, "SELECT 2 AS chen_rejected", true)
		m.done(s, rejected.ID, "failed", "command_rejected_by_acl")
		required := m.submit(s, c, "SELECT 3 AS chen_review", false)
		m.done(s, required.ID, "failed", "command_review_required")
		warning := m.submit(s, c, "SELECT 4 AS chen_warning", false)
		if waiting := m.done(s, warning.ID, "failed", "command_warning_confirmation_required"); len(waiting.ResultIDs) != 0 {
			t.Fatal("unconfirmed warning executed SQL")
		}
		confirmed := m.submitConfirmed(s, c, "SELECT 4 AS chen_warning", false)
		if done := m.done(s, confirmed.ID, "succeeded", ""); len(done.ResultIDs) != 1 {
			t.Fatal("confirmed warning must execute exactly once")
		}

		// Reject a later statement before creating a ticket for the first one.
		mixed := m.submit(s, c, "SELECT 3 AS chen_review; SELECT 2 AS chen_rejected", true)
		if e := m.done(s, mixed.ID, "failed", "command_rejected_by_acl"); e.Review != nil {
			t.Fatal("review was submitted before all batch denials were checked")
		}

		batch := "SELECT 3 AS chen_review; SELECT 4 AS chen_warning"
		approved := m.submitConfirmed(s, c, batch, true)
		ticket := m.pending(s, approved.ID)
		m.ticketCommand(ticket, batch)
		m.core("PATCH", "/api/v1/tickets/apply-command-tickets/"+ticket+"/approve/", true, map[string]any{}, 200, nil)
		view := m.done(s, approved.ID, "succeeded", "")
		if view.Review == nil || view.Review.State != "approved" || len(view.ResultIDs) != 2 {
			t.Fatal("approved immutable batch did not produce both query results")
		}
		denied := m.submit(s, c, "SELECT 5 AS chen_review", true)
		ticket = m.pending(s, denied.ID)
		m.core("PUT", "/api/v1/tickets/apply-command-tickets/"+ticket+"/reject/", true, map[string]any{}, 200, nil)
		m.done(s, denied.ID, "failed", "command_review_rejected")
		canceled := m.submit(s, c, "SELECT 6 AS chen_review", true)
		ticket = m.pending(s, canceled.ID)
		m.chen("POST", "/executions/"+canceled.ID+"/cancel", s.Token, nil, 202, nil)
		m.done(s, canceled.ID, "canceled", "command_review_canceled")
		m.ticketClosed(ticket)

		pausedReview := m.submit(s, c, "SELECT 12 AS chen_review", true)
		ticket = m.pending(s, pausedReview.ID)
		m.core("POST", "/api/v1/terminal/tasks/toggle-lock-session/", true, map[string]any{"session_id": coreID, "task_name": "lock_session"}, 200, nil)
		m.sessionState(s, "locked")
		m.done(s, pausedReview.ID, "canceled", "command_review_canceled")
		m.ticketClosed(ticket)
		m.chen("POST", "/consoles/"+c.ID+"/executions", s.Token, map[string]any{"sql": "SELECT 7", "request_id": integrationKey()}, 403, nil)
		m.core("POST", "/api/v1/terminal/tasks/toggle-lock-session/", true, map[string]any{"session_id": coreID, "task_name": "unlock_session"}, 200, nil)
		m.sessionState(s, "ready")
		m.done(s, m.submit(s, c, "SELECT 8 AS chen_unlocked", false).ID, "succeeded", "")
		// Core audit storage, rather than Chen's local recorded flag, is the
		// assertion boundary. The approved mixed batch also needs warning risk.
		m.audit(coreID, map[string][]int{
			"SELECT 1 AS chen_allowed": {0}, "SELECT 2 AS chen_rejected": {5},
			"SELECT 4 AS chen_warning": {0, 4, 4}, batch: {7},
			"SELECT 5 AS chen_review": {6}, "SELECT 6 AS chen_review": {8},
			"SELECT 12 AS chen_review": {8},
		})
		m.warningNotifications(coreID, 2)
		m.heartbeat(coreID)
		m.core("POST", "/api/v1/terminal/tasks/kill-session/", true, []string{coreID}, 200, nil)
		m.sessionState(s, "closed")
		m.finished(coreID)
		t.Log("real Core/Wisp: allow/reject, explicit review, full-batch approve, reject, cancel, warning audit and reviewer site messages, fresh component heartbeat, lock/unlock/kill and finished session verified")
	})
	t.Run("review_timeout", func(t *testing.T) {
		m := newManagedLive(t, f, 2*time.Second)
		s, c, coreID := m.session()
		e := m.submit(s, c, "SELECT 9 AS chen_review", true)
		ticket := m.pending(s, e.ID)
		m.done(s, e.ID, "failed", "command_review_timed_out")
		m.ticketClosed(ticket)
		m.audit(coreID, map[string][]int{"SELECT 9 AS chen_review": {8}})
		m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
		m.finished(coreID)
		t.Log("real review deadline canceled its Core ticket and persisted review-cancel audit")
	})
	t.Run("permissions_and_masking", func(t *testing.T) {
		m := newManagedLive(t, f, 30*time.Second)
		permission := m.permission()
		permissionURL := "/api/v1/perms/asset-permissions/" + permission.ID + "/"
		t.Cleanup(func() {
			m.core("PATCH", permissionURL, true, map[string]any{"actions": permission.actionValues(), "is_active": permission.Active}, 200, nil)
		})
		m.core("PATCH", permissionURL, true, map[string]any{"actions": []string{"connect"}}, 200, nil)
		s, c, coreID := m.session()
		if s.Session.Permissions.Copy || s.Session.Permissions.Paste || s.Session.Permissions.Download || s.Session.Permissions.Upload {
			t.Fatal("disabled Core clipboard/transfer permissions were widened")
		}
		m.done(s, m.submit(s, c, "SELECT 10 AS chen_restricted", false).ID, "succeeded", "")
		m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
		m.finished(coreID)
		m.core("PATCH", permissionURL, true, map[string]any{"actions": permission.actionValues()}, 200, nil)
		m.core("PATCH", permissionURL, true, map[string]any{"is_active": false}, 200, nil)
		m.core("POST", "/api/v1/authentication/connection-token/", false, map[string]any{"asset": f.AssetID, "account": f.Account, "protocol": "postgresql", "connect_method": "web_gui"}, 400, nil)
		m.core("PATCH", permissionURL, true, map[string]any{"is_active": permission.Active}, 200, nil)

		var rule struct {
			ID string `json:"id"`
		}
		m.core("POST", "/api/v1/acls/data-masking-rules/", true, map[string]any{
			"name": "chen_managed_mask_" + integrationKey(), "users": map[string]any{"type": "ids", "ids": []string{f.OperatorID}},
			"assets": map[string]any{"type": "ids", "ids": []string{f.AssetID}}, "accounts": []string{"*"},
			"fields_pattern": "chen_secret*", "masking_method": "fixed_char", "mask_pattern": "[protected]", "action": "reject", "priority": 1,
		}, 201, &rule)
		if !managedUUID.MatchString(rule.ID) {
			t.Fatal("mask rule ID missing")
		}
		t.Cleanup(func() { m.core("DELETE", "/api/v1/acls/data-masking-rules/"+rule.ID+"/", true, nil, 204, nil) })
		s, c, coreID = m.session()
		e := m.done(s, m.submit(s, c, "SELECT 'synthetic-secret' AS chen_secret_value, 'synthetic-alias' AS renamed, NULL::text AS chen_secret_null", false).ID, "succeeded", "")
		if len(e.ResultIDs) != 1 {
			t.Fatal("masked query result missing")
		}
		var result Result
		m.chen("GET", "/results/"+e.ResultIDs[0]+"/rows", s.Token, nil, 200, &result)
		if len(result.Rows) != 1 || len(result.Rows[0]) != 3 || result.Rows[0][0] != "[protected]" || result.Rows[0][1] != "####" || result.Rows[0][2] != "[protected]" {
			t.Fatal("Core field masking or conservative unknown-origin alias protection failed")
		}
		m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
		m.finished(coreID)
		t.Log("Core clipboard/transfer restrictions, revoked new-connection denial, and synthetic field/null/alias masking verified; fixture policy restored")
	})
	t.Run("warning_only_after_matching_sql_sent", func(t *testing.T) {
		m := newManagedLive(t, f, 30*time.Second)
		var group, acl struct {
			ID string `json:"id"`
		}
		m.core("POST", "/api/v1/acls/command-groups/", true, map[string]any{"name": "chen_qa_anchored_" + integrationKey(), "type": "regex", "content": "^SELECT 2 AS chen_unreached_warn$", "ignore_case": true}, 201, &group)
		t.Cleanup(func() { m.core("DELETE", "/api/v1/acls/command-groups/"+group.ID+"/", true, nil, 204, nil) })
		m.core("POST", "/api/v1/acls/command-filter-acls/", true, map[string]any{
			"name": "chen_qa_anchored_" + integrationKey(), "users": map[string]any{"type": "ids", "ids": []string{f.OperatorID}},
			"assets": map[string]any{"type": "ids", "ids": []string{f.AssetID}}, "accounts": []string{"*"}, "priority": 1,
			"action": "notify_and_warn", "command_groups": []string{group.ID}, "reviewers": []string{f.ReviewerID},
		}, 201, &acl)
		t.Cleanup(func() { m.core("DELETE", "/api/v1/acls/command-filter-acls/"+acl.ID+"/", true, nil, 204, nil) })
		s, c, coreID := m.session()
		batch := "SELECT 1/0; SELECT 2 AS chen_unreached_warn"
		m.done(s, m.submitConfirmed(s, c, batch, false).ID, "failed", "22012")
		malformed := "SELECT 'chen_warning"
		m.done(s, m.submit(s, c, malformed, false).ID, "failed", "sql_parse_error")
		m.audit(coreID, map[string][]int{batch: {0}, malformed: {0}})
		m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
		m.finished(coreID)
		t.Log("unexecuted anchored warning and parse failure persisted as normal audit without warning escalation")
	})
}

// The stock Wisp checks permissions every five minutes. This additional opt-in
// test waits for that real cycle without injecting a task or altering clocks.
func TestManagedLivePermissionRevocation(t *testing.T) {
	if os.Getenv("CHEN_MANAGED_REVOCATION") != "1" {
		t.Skip("set CHEN_MANAGED_REVOCATION=1 to wait for Wisp's real five-minute permission check")
	}
	f := loadManagedFixture(t)
	m := newManagedLive(t, f, 30*time.Second)
	permission := m.permission()
	path := "/api/v1/perms/asset-permissions/" + permission.ID + "/"
	t.Cleanup(func() { m.core("PATCH", path, true, map[string]any{"is_active": permission.Active}, 200, nil) })
	s, c, coreID := m.session()
	m.done(s, m.submit(s, c, "SELECT 11 AS chen_before_revoke", false).ID, "succeeded", "")
	m.core("PATCH", path, true, map[string]any{"is_active": false}, 200, nil)
	started := time.Now()
	m.until(6*time.Minute, func() bool {
		m.server.mu.RLock()
		current := m.server.sessions[tokenKey(s.Token)]
		m.server.mu.RUnlock()
		if current == nil {
			t.Fatal("permission revocation unexpectedly closed without established grace")
		}
		current.mu.Lock()
		defer current.mu.Unlock()
		for _, event := range current.history {
			if event.Type == "session.control" {
				if control, ok := event.Data.(jumpserver.Control); ok && control.Type == "permission_revoked" {
					return true
				}
			}
		}
		return false
	}, "Wisp permission_revoked control after real authorization change")
	t.Logf("real Core permission revocation reached Chen after %s; established ten-minute grace retained", time.Since(started).Round(time.Second))
	m.core("PATCH", path, true, map[string]any{"is_active": permission.Active}, 200, nil)
	m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
	m.finished(coreID)
}

type managedFixture struct {
	CoreURL       string `json:"core_url"`
	WispAddress   string `json:"wisp_address"`
	OperatorToken string `json:"operator_token"`
	OperatorID    string `json:"operator_id"`
	ReviewerID    string `json:"reviewer_id"`
	ReviewerToken string `json:"reviewer_token"`
	OrgID         string `json:"org_id"`
	AssetID       string `json:"asset_id"`
	Account       string `json:"account"`
}

type managedPermission struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Active  bool   `json:"is_active"`
	Actions []struct {
		Value string `json:"value"`
	} `json:"actions"`
	Users []struct {
		ID string `json:"id"`
	} `json:"users"`
	Assets []struct {
		ID string `json:"id"`
	} `json:"assets"`
}

func (p managedPermission) actionValues() []string {
	values := make([]string, 0, len(p.Actions))
	for _, action := range p.Actions {
		values = append(values, action.Value)
	}
	return values
}
func (m *managedLive) permission() managedPermission {
	m.t.Helper()
	var raw json.RawMessage
	m.core("GET", "/api/v1/perms/asset-permissions/?name=Chen%20QA%20permission", true, nil, 200, &raw)
	var entries []managedPermission
	if len(raw) > 0 && raw[0] == '[' {
		_ = json.Unmarshal(raw, &entries)
	} else {
		var page struct {
			Results []managedPermission `json:"results"`
		}
		_ = json.Unmarshal(raw, &page)
		entries = page.Results
	}
	if len(entries) != 1 {
		m.t.Fatal("expected one dedicated QA permission")
	}
	var p managedPermission
	m.core("GET", "/api/v1/perms/asset-permissions/"+entries[0].ID+"/", true, nil, 200, &p)
	if p.Name != "Chen QA permission" || !p.Active || len(p.Users) != 1 || p.Users[0].ID != m.fixture.OperatorID || len(p.Assets) != 1 || p.Assets[0].ID != m.fixture.AssetID {
		m.t.Fatal("refusing to mutate a permission not scoped exactly to the isolated QA operator/asset")
	}
	return p
}

func loadManagedFixture(t *testing.T) managedFixture {
	t.Helper()
	path := os.Getenv("CHEN_MANAGED_FIXTURE")
	if path == "" {
		t.Skip("set CHEN_MANAGED_FIXTURE to an isolated local Core/Wisp fixture")
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm()&0077 != 0 {
		t.Fatal("managed fixture must exist and be private (0600)")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("managed fixture unreadable")
	}
	var f managedFixture
	if json.Unmarshal(data, &f) != nil {
		t.Fatal("managed fixture is invalid")
	}
	u, err := url.Parse(f.CoreURL)
	host, _, hostErr := net.SplitHostPort(f.WispAddress)
	if err != nil || u.Scheme != "http" || u.Hostname() != "127.0.0.1" || hostErr != nil || host != "127.0.0.1" {
		t.Fatal("managed tests require an isolated loopback Core and Wisp")
	}
	if f.OperatorToken == "" || f.ReviewerToken == "" || f.OperatorID == "" || f.ReviewerID == "" || f.OrgID == "" || f.AssetID == "" || f.Account == "" {
		t.Fatal("managed fixture incomplete")
	}
	return f
}

type managedLive struct {
	t       *testing.T
	fixture managedFixture
	server  *Server
	url     string
	client  *http.Client
}

func newManagedLive(t *testing.T, f managedFixture, reviewTimeout time.Duration) *managedLive {
	t.Helper()
	conn, err := grpc.NewClient(f.WispAddress, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal("live Wisp client creation failed")
	}
	p, err := jumpserver.NewWispProvider(protobuf.NewServiceClient(conn), jumpserver.Options{SpoolDir: t.TempDir(), RequestTimeout: 10 * time.Second, RetryInterval: 100 * time.Millisecond, CheckInterval: 50 * time.Millisecond, ReviewPollInterval: 100 * time.Millisecond, ReviewTimeout: reviewTimeout})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { p.Run(ctx); close(done) }()
	v := New(p, Options{StateDir: t.TempDir(), QueryTimeout: 15 * time.Second, MaxRows: 100})
	h := httptest.NewServer(v.Handler())
	t.Cleanup(func() { h.Close(); v.Close(); cancel(); <-done; conn.Close() })
	// Initial stream setup is asynchronous and must precede token consumption.
	time.Sleep(100 * time.Millisecond)
	return &managedLive{t: t, fixture: f, server: v, url: h.URL, client: &http.Client{Timeout: 20 * time.Second}}
}

func (m *managedLive) request(method, endpoint, auth string, body any, status int, out any) {
	m.t.Helper()
	data, err := json.Marshal(body)
	if err != nil {
		m.t.Fatal("request encoding failed")
	}
	r, err := http.NewRequest(method, endpoint, bytes.NewReader(data))
	if err != nil {
		m.t.Fatal("request construction failed")
	}
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Authorization", auth)
	r.Header.Set("X-JMS-ORG", m.fixture.OrgID)
	resp, err := m.client.Do(r)
	if err != nil {
		m.t.Fatal("managed HTTP transport failed")
	}
	defer resp.Body.Close()
	data, err = io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		m.t.Fatal("managed response unreadable")
	}
	if resp.StatusCode != status {
		var safe struct {
			Error struct {
				Code string `json:"code"`
			} `json:"error"`
		}
		_ = json.Unmarshal(data, &safe)
		m.t.Fatalf("%s %s HTTP %d want %d code=%s (response withheld)", method, r.URL.Path, resp.StatusCode, status, safe.Error.Code)
	}
	if out != nil && json.Unmarshal(data, out) != nil {
		m.t.Fatal("managed response JSON invalid")
	}
}
func (m *managedLive) core(method, path string, reviewer bool, body any, status int, out any) {
	m.t.Helper()
	token := m.fixture.OperatorToken
	if reviewer {
		token = m.fixture.ReviewerToken
	}
	m.request(method, m.fixture.CoreURL+path, "Token "+token, body, status, out)
}
func (m *managedLive) chen(method, path, token string, body any, status int, out any) {
	m.t.Helper()
	m.request(method, m.url+"/chen/api/v1"+path, "Bearer "+token, body, status, out)
}
func (m *managedLive) session() (integrationSession, ConsoleView, string) {
	m.t.Helper()
	var token struct {
		ID string `json:"id"`
	}
	m.core("POST", "/api/v1/authentication/connection-token/", false, map[string]any{"asset": m.fixture.AssetID, "account": m.fixture.Account, "protocol": "postgresql", "connect_method": "web_gui"}, 201, &token)
	var s integrationSession
	m.chen("POST", "/sessions", "", map[string]any{"connect_token": token.ID, "request_id": integrationKey()}, 201, &s)
	var c ConsoleView
	m.chen("POST", "/sessions/"+s.Session.ID+"/consoles", s.Token, map[string]any{}, 201, &c)
	m.server.mu.RLock()
	coreID := m.server.sessions[tokenKey(s.Token)].grant.ID
	m.server.mu.RUnlock()
	return s, c, coreID
}
func (m *managedLive) submit(s integrationSession, c ConsoleView, sql string, review bool) ExecutionView {
	m.t.Helper()
	var e ExecutionView
	m.chen("POST", "/consoles/"+c.ID+"/executions", s.Token, map[string]any{"sql": sql, "request_id": integrationKey(), "submit_review": review}, 202, &e)
	return e
}
func (m *managedLive) done(s integrationSession, id, status, code string) ExecutionView {
	m.t.Helper()
	var e ExecutionView
	m.until(20*time.Second, func() bool {
		m.chen("GET", "/executions/"+id, s.Token, nil, 200, &e)
		return terminal(e.Status) && e.FinishedAt != nil
	}, "execution completion")
	actual := ""
	if e.Error != nil {
		actual = e.Error.Code
	}
	if e.Status != status || actual != code {
		m.t.Fatalf("execution %s/%s want %s/%s", e.Status, actual, status, code)
	}
	if e.AuditStatus != "recorded" {
		m.t.Fatalf("local audit=%s", e.AuditStatus)
	}
	return e
}

var managedUUID = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)

func (m *managedLive) pending(s integrationSession, id string) string {
	m.t.Helper()
	var e ExecutionView
	m.until(10*time.Second, func() bool {
		m.chen("GET", "/executions/"+id, s.Token, nil, 200, &e)
		if terminal(e.Status) {
			code := ""
			if e.Error != nil {
				code = e.Error.Code
			}
			m.t.Fatalf("review ended before pending: %s/%s", e.Status, code)
		}
		return e.Review != nil && e.Review.State == "pending"
	}, "review pending")
	ticket := managedUUID.FindString(e.Review.TicketURL)
	if ticket == "" || len(e.Review.Reviewers) == 0 || len(e.ResultIDs) > 0 {
		m.t.Fatal("pending review metadata invalid or query executed early")
	}
	return ticket
}
func (m *managedLive) ticketCommand(id, sql string) {
	m.t.Helper()
	var ticket struct {
		SQL string `json:"apply_run_command"`
	}
	m.core("GET", "/api/v1/tickets/apply-command-tickets/"+id+"/", true, nil, 200, &ticket)
	if ticket.SQL != sql {
		m.t.Fatal("Core review ticket changed/truncated immutable SQL batch")
	}
}
func (m *managedLive) ticketClosed(id string) {
	m.t.Helper()
	m.until(10*time.Second, func() bool {
		var ticket struct {
			Status any `json:"status"`
		}
		m.core("GET", "/api/v1/tickets/apply-command-tickets/"+id+"/", true, nil, 200, &ticket)
		value, _ := json.Marshal(ticket.Status)
		return strings.Contains(string(value), "closed")
	}, "Core ticket cancellation")
}
func (m *managedLive) sessionState(s integrationSession, state string) {
	m.t.Helper()
	if state == "closed" {
		m.until(45*time.Second, func() bool {
			m.server.mu.RLock()
			defer m.server.mu.RUnlock()
			return m.server.sessions[tokenKey(s.Token)] == nil
		}, "managed session removal")
		m.chen("GET", "/sessions/"+s.Session.ID, s.Token, nil, 401, nil)
		return
	}
	m.until(45*time.Second, func() bool {
		var view SessionView
		m.chen("GET", "/sessions/"+s.Session.ID, s.Token, nil, 200, &view)
		return view.State == state
	}, "managed session state "+state)
}
func (m *managedLive) finished(id string) {
	m.t.Helper()
	m.until(15*time.Second, func() bool {
		var view struct {
			Finished   bool  `json:"is_finished"`
			Replay     bool  `json:"has_replay"`
			ReplaySize int64 `json:"replay_size"`
		}
		m.core("GET", "/api/v1/terminal/sessions/"+id+"/", true, nil, 200, &view)
		return view.Finished && view.Replay && view.ReplaySize > 0
	}, "Core session finish and durable replay upload")
}
func (m *managedLive) heartbeat(sessionID string) {
	m.t.Helper()
	var session struct {
		Terminal struct {
			ID string `json:"id"`
		} `json:"terminal"`
	}
	m.core("GET", "/api/v1/terminal/sessions/"+sessionID+"/", true, nil, 200, &session)
	if !managedUUID.MatchString(session.Terminal.ID) {
		m.t.Fatal("Core session lacks component identity")
	}
	// Core caches Status rather than saving its auto_now_add timestamp. Each
	// real heartbeat carries a new Status UUID, so compare identities rather
	// than treating its nullable date_created field as a timestamp.
	type terminalStatus struct {
		Alive bool `json:"is_alive"`
		Stat  struct {
			ID     string `json:"id"`
			Online int    `json:"session_online"`
		} `json:"stat"`
	}
	var initial terminalStatus
	path := "/api/v1/terminal/terminals/" + session.Terminal.ID + "/"
	m.core("GET", path, true, nil, 200, &initial)
	m.until(45*time.Second, func() bool {
		var terminal terminalStatus
		m.core("GET", path, true, nil, 200, &terminal)
		return terminal.Alive && terminal.Stat.ID != "" && terminal.Stat.ID != initial.Stat.ID && terminal.Stat.Online > 0
	}, "fresh Wisp component heartbeat in Core after session creation")
}
func (m *managedLive) audit(id string, want map[string][]int) {
	m.t.Helper()
	var last []string
	defer func() {
		if m.t.Failed() {
			m.t.Logf("observed audit classifications: %v", last)
		}
	}()
	m.until(35*time.Second, func() bool {
		var raw json.RawMessage
		m.core("GET", "/api/v1/terminal/commands/?session_id="+url.QueryEscape(id)+"&limit=200", true, nil, 200, &raw)
		var page struct {
			Results []struct {
				Input string `json:"input"`
				Risk  any    `json:"risk_level"`
			} `json:"results"`
		}
		if len(raw) > 0 && raw[0] == '[' {
			_ = json.Unmarshal(raw, &page.Results)
		} else {
			_ = json.Unmarshal(raw, &page)
		}
		last = nil
		found := map[string]map[int]int{}
		for _, entry := range page.Results {
			riskJSON, _ := json.Marshal(entry.Risk)
			last = append(last, entry.Input+" risk="+string(riskJSON))
			var risk int
			switch r := entry.Risk.(type) {
			case float64:
				risk = int(r)
			case map[string]any:
				if value, ok := r["value"].(float64); ok {
					risk = int(value)
				} else {
					continue
				}
			default:
				continue
			}
			if found[entry.Input] == nil {
				found[entry.Input] = map[int]int{}
			}
			found[entry.Input][risk]++
		}
		for sql, risks := range want {
			allowed := map[int]bool{}
			for _, risk := range risks {
				allowed[risk] = true
			}
			for risk := range found[sql] {
				if !allowed[risk] {
					m.t.Fatalf("unexpected Core audit risk %d for synthetic SQL", risk)
				}
			}
			for _, risk := range risks {
				if found[sql][risk] < 1 {
					return false
				}
				found[sql][risk]--
			}
		}
		return true
	}, "Core command audit records")
}
func (m *managedLive) warningNotifications(sessionID string, minimum int) {
	m.t.Helper()
	m.until(35*time.Second, func() bool {
		var raw json.RawMessage
		m.core("GET", "/api/v1/notifications/site-messages/?limit=200", true, nil, 200, &raw)
		type entry struct {
			Content struct {
				Subject string `json:"subject"`
				Message string `json:"message"`
			} `json:"content"`
		}
		var entries []entry
		if len(raw) > 0 && raw[0] == '[' {
			_ = json.Unmarshal(raw, &entries)
		} else {
			var page struct {
				Results []entry `json:"results"`
			}
			_ = json.Unmarshal(raw, &page)
			entries = page.Results
		}
		count := 0
		for _, e := range entries {
			if strings.Contains(e.Content.Subject, "chen_warning") && strings.Contains(e.Content.Message, sessionID) {
				count++
			}
		}
		return count >= minimum
	}, "Core reviewer warning notifications for this session")
}
func (m *managedLive) until(timeout time.Duration, check func() bool, description string) {
	m.t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	m.t.Fatal(description + " not observed before deadline")
}

func (m *managedLive) submitConfirmed(s integrationSession, c ConsoleView, sql string, review bool) ExecutionView {
	m.t.Helper()
	var e ExecutionView
	m.chen("POST", "/consoles/"+c.ID+"/executions", s.Token, map[string]any{"sql": sql, "request_id": integrationKey(), "submit_review": review, "confirm_warnings": true}, 202, &e)
	return e
}
