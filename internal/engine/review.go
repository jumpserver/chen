package engine

import (
	"errors"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

// The SQL deadline includes queueing and database work, but an operator waiting
// for a reviewer gets the separate, bounded approval deadline from the provider.
func (e *execution) startDeadline(d time.Duration) {
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
	// Preflight every original statement before creating any ticket. A reject
	// later in a script must prevent both execution and unnecessary approvals.
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
	if warningRequired != nil && !e.confirmWarnings {
		return warningRequired
	}
	if required != nil && !e.submitReview {
		return required
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
	for _, command := range commands {
		key := reviews[command]
		if seen[command] || key != "" && approved[key] {
			continue
		}
		seen[command] = true
		decision, err := authorizer.AuthorizeCommand(e.ctx, s.grant, jumpserver.AuthorizationRequest{
			SQL: command, BatchSQL: e.sql, SubmitReview: e.submitReview, ConfirmWarnings: e.confirmWarnings,
			OnReview: func(review jumpserver.ReviewStatus) {
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
