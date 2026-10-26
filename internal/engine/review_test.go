package engine

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

type reviewExecutionProvider struct {
	executionProvider
	calls      atomic.Int32
	ready      chan string
	release    chan struct{}
	onApproval func()
}

func (p *reviewExecutionProvider) Authorize(ctx context.Context, _ *jumpserver.Grant, command string) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if strings.Contains(command, "requires_review") {
		return &jumpserver.PolicyError{Code: "command_review_required", ACLID: "review-acl", GroupID: "review-group", RiskLevel: 3}
	}
	if strings.Contains(command, "rejected") {
		return &jumpserver.PolicyError{Code: "command_rejected_by_acl", ACLID: "reject-acl", GroupID: "reject-group", RiskLevel: 2}
	}
	return nil
}

func (p *reviewExecutionProvider) AuthorizeCommand(ctx context.Context, _ *jumpserver.Grant, request jumpserver.AuthorizationRequest) (jumpserver.AuthorizationDecision, error) {
	if strings.Contains(request.SQL, "requires_review") {
		p.calls.Add(1)
		if !request.SubmitReview {
			panic("review must be explicit")
		}
		status := jumpserver.ReviewStatus{State: "pending", ExpiresAt: time.Now().Add(time.Minute)}
		request.OnReview(status)
		if p.ready != nil {
			p.ready <- request.BatchSQL
		}
		if p.release != nil {
			select {
			case <-p.release:
			case <-ctx.Done():
				status.State = "canceled"
				request.OnReview(status)
				return jumpserver.AuthorizationDecision{}, &jumpserver.PolicyError{Code: "command_review_canceled", RiskLevel: 5, ACLID: "review-acl", GroupID: "review-group"}
			}
		}
		status.State = "approved"
		request.OnReview(status)
		if p.onApproval != nil {
			p.onApproval()
		}
		return jumpserver.AuthorizationDecision{RiskLevel: 4, ACLID: "review-acl", GroupID: "review-group"}, nil
	}
	if strings.HasPrefix(strings.TrimSpace(request.SQL), "SELECT 2 AS warns") {
		return jumpserver.AuthorizationDecision{RiskLevel: 1, ACLID: "warning-acl", GroupID: "warning-group"}, nil
	}
	return jumpserver.AuthorizationDecision{}, nil
}

func TestExecutionReviewRequiresExplicitSubmission(t *testing.T) {
	var queries atomic.Int32
	v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 AS requires_review", &executionDriverState{query: func(context.Context, string) (driver.Rows, error) { queries.Add(1); return &executionRows{}, nil }})
	p := &reviewExecutionProvider{}
	v.provider = p
	v.run(s, c, e)
	if got := e.snapshot(); got.Status != "failed" || got.Error.Code != "command_review_required" || p.calls.Load() != 0 || queries.Load() != 0 {
		t.Fatalf("review executed without explicit submission: %+v", got)
	}
}

func TestExecutionReviewPreflightsLaterRejectBeforeCreatingTicket(t *testing.T) {
	v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 AS requires_review; SELECT 2 AS rejected", &executionDriverState{})
	p := &reviewExecutionProvider{}
	v.provider = p
	e.submitReview = true
	v.run(s, c, e)
	if got := e.snapshot(); got.Error.Code != "command_rejected_by_acl" || p.calls.Load() != 0 || len(got.ResultIDs) != 0 {
		t.Fatalf("later rejection did not precede ticket creation: %+v", got)
	}
}

func TestExecutionReviewKeepsBatchAndPausesSQLDeadline(t *testing.T) {
	command := "SELECT 1 AS requires_review; SELECT 2 AS requires_review"
	v, s, c, e := executionFixture(t, "postgresql", command, &executionDriverState{})
	p := &reviewExecutionProvider{ready: make(chan string, 2), release: make(chan struct{})}
	v.provider = p
	e.submitReview = true
	e.startDeadline(200 * time.Millisecond)
	done := make(chan struct{})
	go func() { v.run(s, c, e); close(done) }()
	select {
	case batch := <-p.ready:
		if batch != command {
			t.Fatal("ticket changed original SQL")
		}
	case <-time.After(time.Second):
		t.Fatal("review did not start")
	}
	if e.snapshot().Status != "waiting_review" {
		t.Fatal("review is not visible")
	}
	time.Sleep(250 * time.Millisecond)
	if e.ctx.Err() != nil {
		t.Fatal("SQL timeout consumed reviewer waiting time")
	}
	approvedAt := time.Now()
	close(p.release)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("approved execution stalled")
	}
	if got := e.snapshot(); got.Status != "succeeded" || got.Review.State != "approved" || p.calls.Load() != 1 || len(got.ResultIDs) != 2 {
		t.Fatalf("approved batch/dedup failed: %+v", got)
	}
	if started := e.snapshot().StartedAt; started == nil || started.Before(approvedAt) {
		t.Fatal("query duration incorrectly includes approval waiting time")
	}
}

func TestExecutionPendingReviewCanCancelWithoutDatabaseCancellation(t *testing.T) {
	v, s, c, e := executionFixture(t, "db2", "SELECT 1 AS requires_review", &executionDriverState{})
	p := &reviewExecutionProvider{ready: make(chan string, 1), release: make(chan struct{})}
	v.provider = p
	e.submitReview = true
	done := make(chan struct{})
	go func() { v.run(s, c, e); close(done) }()
	select {
	case <-p.ready:
	case <-time.After(time.Second):
		t.Fatal("review did not start")
	}
	// Pending tickets do not need the DB's active cancellation capability.
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/executions/"+e.view.ID+"/cancel", "{}", "token"))
	if w.Code != 202 {
		t.Fatalf("pending review cancellation denied: %d", w.Code)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancel did not terminate review")
	}
	if got := e.snapshot(); got.Status != "canceled" || got.Review.State != "canceled" || len(got.ResultIDs) != 0 {
		t.Fatalf("cancel ran SQL: %+v", got)
	}
}

func TestExecutionReviewDoesNotHideMixedWarningAudit(t *testing.T) {
	v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 AS requires_review; SELECT 2 AS warns", &executionDriverState{})
	p := &reviewExecutionProvider{}
	v.provider = p
	e.submitReview = true
	v.run(s, c, e)
	if got := e.snapshot(); got.Status != "succeeded" {
		t.Fatalf("execution failed: %+v", got)
	}
	if len(p.records) != 2 || p.records[0].RiskLevel != 4 || p.records[1].RiskLevel != 1 || p.records[1].ACLID != "warning-acl" {
		t.Fatalf("mixed warning suppressed: %+v", p.records)
	}
}

func TestExecutionReviewPreflightDoesNotWarnForUnexecutedStatement(t *testing.T) {
	var queries atomic.Int32
	v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 / 0; SELECT 2 AS warns", &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		queries.Add(1)
		return nil, errors.New("synthetic first-statement failure")
	}})
	p := &reviewExecutionProvider{}
	v.provider = p
	v.run(s, c, e)
	if queries.Load() != 1 || len(p.records) != 1 || p.records[0].RiskLevel != 0 || p.records[0].ACLID != "" {
		t.Fatalf("unexecuted later warning produced audit or notification: %+v", p.records)
	}
}

func TestExecutionReviewApprovedAfterCancellationDoesNotReturnToRunning(t *testing.T) {
	var queries atomic.Int32
	v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 AS requires_review", &executionDriverState{query: func(context.Context, string) (driver.Rows, error) {
		queries.Add(1)
		return &executionRows{}, nil
	}})
	p := &reviewExecutionProvider{onApproval: func() {
		w := httptest.NewRecorder()
		v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/executions/"+e.view.ID+"/cancel", "{}", "token"))
		if w.Code != 202 {
			t.Fatalf("cancel status=%d", w.Code)
		}
	}}
	v.provider = p
	e.submitReview = true
	v.run(s, c, e)
	if e.snapshot().Status != "canceled" || queries.Load() != 0 {
		t.Fatal("cancellation did not prevent an approved query")
	}
	for _, event := range s.history {
		if view, ok := event.Data.(ExecutionView); ok && view.Status == "running" {
			t.Fatal("execution returned to running after cancellation")
		}
	}
}

func TestExecutionReviewChoiceIsPartOfIdempotency(t *testing.T) {
	v, _, c, e := executionFixture(t, "postgresql", "SELECT 1", &executionDriverState{})
	c.requests["request"] = e
	e.submitReview = true
	body, _ := json.Marshal(map[string]any{"sql": e.sql, "request_id": "request", "submit_review": false})
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/"+c.id+"/executions", string(body), "token"))
	if w.Code != 409 {
		t.Fatalf("review consent changed under same request ID: %d", w.Code)
	}
}
