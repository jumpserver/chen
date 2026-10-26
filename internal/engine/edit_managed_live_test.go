package engine

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"
)

// Explicitly opted-in writes use only a new disposable table in the isolated
// local Core fixture. Policies, tickets, control events and audits use real Wisp.
func TestGridEditsManagedLive(t *testing.T) {
	if os.Getenv("CHEN_WRITE_MANAGED") != "1" {
		t.Skip("set CHEN_WRITE_MANAGED=1 for isolated managed write acceptance")
	}
	f := loadManagedFixture(t)
	m := newManagedLive(t, f, 30*time.Second)
	s, c, coreID := m.session()
	name := "CHEN_M_" + strings.ToUpper(integrationKey()[:12])
	table := quoteDatabaseIdentifier(c.Schema, "postgresql") + "." + quoteDatabaseIdentifier(name, "postgresql")
	m.done(s, m.submit(s, c, "CREATE TABLE "+table+" (id bigint PRIMARY KEY, title text)", false).ID, "succeeded", "")
	m.server.mu.RLock()
	cfg := m.server.sessions[tokenKey(s.Token)].grant.Config
	m.server.mu.RUnlock()
	observer, err := openDatabase(cfg, 1)
	if err != nil {
		t.Fatal("cannot open disposable table observer")
	}
	defer observer.Close()
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := observer.ExecContext(ctx, "DROP TABLE "+table); err != nil {
			t.Error("disposable managed table cleanup failed")
		}
	}()
	base := "/consoles/" + c.ID
	preview := func(value string, source string, status int) (string, string) {
		t.Helper()
		var e ExecutionView
		m.chen("POST", base+"/browse", s.Token, map[string]any{"schema": c.Schema, "table": name, "request_id": integrationKey()}, 202, &e)
		e = m.done(s, e.ID, "succeeded", "")
		if len(e.ResultIDs) != 1 {
			t.Fatal("browse returned no result")
		}
		var p struct {
			PlanID string `json:"plan_id"`
			SQL    string `json:"sql"`
		}
		m.chen("POST", base+"/results/"+e.ResultIDs[0]+"/edits/preview", s.Token, editPreviewRequest{Source: source, Changes: []editChange{{Op: "insert", Values: map[string]*string{"0": editString("1"), "1": editString(value)}}}}, status, &p)
		return p.PlanID, p.SQL
	}
	apply := func(plan string, review, confirm bool) ExecutionView {
		t.Helper()
		var e ExecutionView
		m.chen("POST", base+"/edits/"+plan+"/apply", s.Token, map[string]any{"request_id": integrationKey(), "submit_review": review, "confirm_warnings": confirm}, 202, &e)
		return e
	}
	count := func(want int) {
		t.Helper()
		var got int
		if err := observer.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&got); err != nil || got != want {
			t.Fatalf("managed write row count=%d want=%d", got, want)
		}
	}
	clear := func() {
		t.Helper()
		if _, err := observer.Exec("DELETE FROM " + table); err != nil {
			t.Fatal("cannot reset owned fixture rows")
		}
	}
	if s.Session.Permissions.Upload {
		t.Fatal("expected restricted QA operator without upload permission")
	}
	preview("csv forbidden", "csv", 403)
	denied, deniedSQL := preview("chen_rejected", "", 200)
	m.done(s, apply(denied, true, false).ID, "failed", "command_rejected_by_acl")
	count(0)
	review, reviewSQL := preview("chen_review", "", 200)
	m.done(s, apply(review, false, false).ID, "failed", "command_review_required")
	count(0)
	e := apply(review, true, false)
	ticket := m.pending(s, e.ID)
	count(0)
	m.ticketCommand(ticket, reviewSQL)
	m.core("PATCH", "/api/v1/tickets/apply-command-tickets/"+ticket+"/approve/", true, map[string]any{}, 200, nil)
	saved := m.done(s, e.ID, "succeeded", "")
	if saved.Edit == nil || !saved.Edit.DatabaseCommitted {
		t.Fatal("approved grid save did not commit")
	}
	count(1)
	clear()
	warning, warningSQL := preview("chen_warning", "", 200)
	m.done(s, apply(warning, false, false).ID, "failed", "command_warning_confirmation_required")
	count(0)
	m.done(s, apply(warning, false, true).ID, "succeeded", "")
	count(1)
	clear()
	paused, pausedSQL := preview("chen_review paused", "", 200)
	e = apply(paused, true, false)
	ticket = m.pending(s, e.ID)
	m.core("POST", "/api/v1/terminal/tasks/toggle-lock-session/", true, map[string]any{"session_id": coreID, "task_name": "lock_session"}, 200, nil)
	m.sessionState(s, "locked")
	m.done(s, e.ID, "canceled", "command_review_canceled")
	m.ticketClosed(ticket)
	count(0)
	m.chen("POST", base+"/edits/"+paused+"/apply", s.Token, map[string]any{"request_id": integrationKey()}, 403, nil)
	m.core("POST", "/api/v1/terminal/tasks/toggle-lock-session/", true, map[string]any{"session_id": coreID, "task_name": "unlock_session"}, 200, nil)
	m.sessionState(s, "ready")
	m.audit(coreID, map[string][]int{deniedSQL: {5}, reviewSQL: {6, 7}, warningSQL: {0, 4}, pausedSQL: {8}})
	m.chen("DELETE", "/sessions/"+s.Session.ID, s.Token, nil, 204, nil)
	m.finished(coreID)
	t.Log("real Core: grid ACL denial, no write before approval/warning, approved save, pause cancellation, CSV upload permission and durable audit/replay passed")
}
