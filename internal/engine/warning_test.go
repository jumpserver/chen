package engine

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/jumpserver/chen/internal/jumpserver"
)

type warningExecutionProvider struct{ reviewExecutionProvider }

func (p *warningExecutionProvider) Authorize(ctx context.Context, g *jumpserver.Grant, sql string) error {
	if strings.Contains(sql, "requires_warning") {
		return &jumpserver.PolicyError{Code: "command_warning_confirmation_required", ACLID: "notice", GroupID: "notice-group", RiskLevel: 1}
	}
	return p.reviewExecutionProvider.Authorize(ctx, g, sql)
}
func (p *warningExecutionProvider) AuthorizeCommand(ctx context.Context, g *jumpserver.Grant, r jumpserver.AuthorizationRequest) (jumpserver.AuthorizationDecision, error) {
	if strings.Contains(r.SQL, "requires_warning") {
		if !r.ConfirmWarnings {
			return jumpserver.AuthorizationDecision{}, p.Authorize(ctx, g, r.SQL)
		}
		return jumpserver.AuthorizationDecision{RiskLevel: 1, ACLID: "notice", GroupID: "notice-group", NotifyWarning: true}, nil
	}
	return p.reviewExecutionProvider.AuthorizeCommand(ctx, g, r)
}
func TestNotifyWarningStopsWholeScriptBeforeAnyQueryOrReview(t *testing.T) {
	for _, confirmed := range []bool{false, true} {
		var sent atomic.Int32
		v, s, c, e := executionFixture(t, "postgresql", "SELECT 1 AS requires_review; SELECT 2 AS requires_warning", &executionDriverState{query: func(context.Context, string) (driver.Rows, error) { sent.Add(1); return &executionRows{}, nil }})
		p := &warningExecutionProvider{}
		v.provider = p
		e.submitReview = true
		e.confirmWarnings = confirmed
		v.run(s, c, e)
		if !confirmed {
			if got := e.snapshot(); got.Error == nil || got.Error.Code != "command_warning_confirmation_required" || sent.Load() != 0 || p.calls.Load() != 0 {
				t.Fatalf("confirmation bypass: %+v", got)
			}
			if len(p.records) != 1 || p.records[0].RiskLevel != 0 {
				t.Fatal("unconfirmed SQL incorrectly generated a warning notification")
			}
		} else if got := e.snapshot(); got.Status != "succeeded" || sent.Load() != 2 || p.calls.Load() != 1 {
			t.Fatalf("confirmed batch did not execute once: %+v", got)
		}
	}
}
func TestWarningConfirmationIsPartOfIdempotency(t *testing.T) {
	v, _, c, e := executionFixture(t, "postgresql", "SELECT 1", &executionDriverState{})
	c.requests["request"] = e
	body, _ := json.Marshal(map[string]any{"sql": e.sql, "request_id": "request", "confirm_warnings": true})
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/consoles/"+c.id+"/executions", string(body), "token"))
	if w.Code != 409 {
		t.Fatalf("confirmation changed under one request id: %d", w.Code)
	}
}
