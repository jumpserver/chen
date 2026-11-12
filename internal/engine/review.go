package engine

import (
	"context"
	"errors"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

// The SQL deadline includes queueing and database work, but an operator waiting
// for a reviewer gets the separate, bounded approval deadline from the provider.
func (e *execution) startDeadline(d time.Duration) {
	if d <= 0 {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.deadlineAt = time.Now().Add(d)
	e.deadlineTimer = time.AfterFunc(d, e.expireDeadline)
}

func (e *execution) pauseDeadline() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if !e.deadlinePaused && e.deadlineTimer != nil && e.deadlineTimer.Stop() {
		e.remaining = time.Until(e.deadlineAt)
		e.deadlinePaused = true
	}
}

func (e *execution) resumeDeadline() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.deadlinePaused {
		e.deadlinePaused = false
		e.deadlineAt = time.Now().Add(e.remaining)
		e.deadlineTimer = time.AfterFunc(e.remaining, e.expireDeadline)
	}
}

func (e *execution) expireDeadline() {
	e.deadlineExpired.Store(true)
	e.cancel()
}

func (e *execution) stopDeadline() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.deadlineTimer != nil {
		e.deadlineTimer.Stop()
	}
}

func (v *Server) authorizeExecution(s *session, e *execution, commands []string, audit *jumpserver.AuditRecord) error {
	var required, warningRequired *jumpserver.PolicyError
	reviews := map[string]string{}
	// Preflight the current statement and its generated commands, or the whole
	// grid edit plan. Ordinary scripts call this separately for each statement.
	for _, command := range commands {
		if err := v.provider.Authorize(e.ctx, s.grant, command); err != nil {
			var policy *jumpserver.PolicyError
			if !errors.As(err, &policy) {
				return err
			}
			if policy.Code == "command_warning_confirmation_required" {
				if warningRequired == nil {
					warningRequired = policy
				}
				continue
			}
			if policy.Code != "command_review_required" {
				return err
			}
			if required == nil {
				required = policy
			}
			reviews[command] = policy.ACLID + "\x00" + policy.GroupID
		}
	}
	// The legacy grid save preview is already the user's batch confirmation;
	// NotifyWarning records its risk there, while query consoles prompt per SQL.
	confirmWarnings, submitReview := e.confirmWarnings || e.edit != nil, e.submitReview
	if warningRequired != nil && !confirmWarnings {
		if err := v.waitExecutionConfirmation(s, e, "warning", audit.SQL); err != nil {
			var policy *jumpserver.PolicyError
			if errors.As(err, &policy) {
				policy.ACLID, policy.GroupID = warningRequired.ACLID, warningRequired.GroupID
			}
			return err
		}
		confirmWarnings = true
	}
	if required != nil && !submitReview {
		if err := v.waitExecutionConfirmation(s, e, "review", audit.SQL); err != nil {
			if errors.Is(err, context.Canceled) {
				err = &jumpserver.PolicyError{Code: "command_review_canceled"}
			}
			var policy *jumpserver.PolicyError
			if errors.As(err, &policy) {
				policy.ACLID, policy.GroupID = required.ACLID, required.GroupID
				policy.RiskLevel = int32(protobuf.RiskLevel_ReviewCancel)
			}
			return err
		}
		submitReview = true
	}
	authorizer, ok := v.provider.(jumpserver.CommandAuthorizer)
	if !ok {
		if warningRequired != nil {
			return warningRequired
		}
		if required != nil {
			return required
		}
		return nil
	}
	defer e.resumeDeadline()
	e.policyWarnings = make(map[string]jumpserver.AuthorizationDecision)
	approved := map[string]bool{}
	seen := map[string]bool{}
	var affectedRows *int64
	if e.edit != nil {
		count := int64(len(e.edit.commands))
		affectedRows = &count
	}
	for _, command := range commands {
		key := reviews[command]
		if seen[command] || key != "" && approved[key] {
			continue
		}
		seen[command] = true
		decision, err := authorizer.AuthorizeCommand(e.ctx, s.grant, jumpserver.AuthorizationRequest{
			SQL: command, BatchSQL: audit.SQL, AffectedRows: affectedRows, SubmitReview: submitReview, ConfirmWarnings: confirmWarnings,
			OnReview: func(review jumpserver.ReviewStatus) {
				review.SQL = command
				if e.edit != nil {
					// Grid identities can contain masked primary-key values. The
					// user's review panel receives the same masked SQL as preview.
					for _, change := range e.edit.commands {
						if change.policySQL == command {
							review.SQL = change.previewSQL
							break
						}
					}
				}
				if review.State == "pending" {
					e.pauseDeadline()
				}
				v.update(s, e, func(view *ExecutionView) {
					view.Review = &review
					if review.State == "pending" && !e.cancelRequested.Load() {
						view.Status = "waiting_review"
					}
				})
			},
		})
		if err != nil {
			return err
		}
		if key != "" {
			approved[key] = true
		}
		if decision.RiskLevel == 1 && decision.ACLID != "" {
			e.policyWarnings[command] = decision
		}
		if decision.RiskLevel != 1 && decision.RiskLevel >= audit.RiskLevel && decision.ACLID != "" {
			audit.RiskLevel, audit.ACLID, audit.GroupID = decision.RiskLevel, decision.ACLID, decision.GroupID
		}
	}
	return s.grant.Check()
}
