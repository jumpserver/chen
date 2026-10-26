package engine

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync/atomic"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

// A grid save is atomic, unlike a normal SQL script. Never route it through run's
// sequential auto-commit statements or replay it after an uncertain commit.
func (v *Server) runEdits(s *session, c *console, e *execution) {
	c.mu.Lock()
	var touched atomic.Bool
	cancelDone := make(chan struct{})
	stopCancel := context.AfterFunc(e.ctx, func() {
		defer close(cancelDone)
		if !jumpserver.IsMySQLWire(s.grant.Config.Dialect) || !touched.Load() || e.cancelRequested.Load() {
			return
		}
		ctx, stop := context.WithTimeout(context.Background(), 2*time.Second)
		defer stop()
		cancelBackend(ctx, s, c, e)
	})
	defer func() {
		if !stopCancel() {
			<-cancelDone
		}
		e.stopDeadline()
		e.cancel()
		if e.cancelRequested.Load() {
			<-e.cancelDone
		}
		c.mu.Unlock()
	}()
	start := time.Now()
	audit := jumpserver.AuditRecord{ID: e.view.ID, SQL: e.sql, StartedAt: start, RiskClassified: true}
	triggeredWarnings := map[string]jumpserver.AuditRecord{}
	defer func() {
		audit.FinishedAt = time.Now()
		view := e.snapshot()
		outcome := "pending"
		if view.Edit != nil {
			outcome = view.Edit.Outcome
		}
		audit.Output = fmt.Sprintf("execution_id=%s edit_plan=%s state=%s outcome=%s", view.ID, e.edit.id, view.Status, outcome)
		if view.Error != nil {
			audit.Error = audit.Output + "; " + view.Error.Message
		}
		ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		status := "recorded"
		if v.provider.Record(ctx, s.grant, audit) != nil {
			status = "failed"
		}
		for _, warning := range triggeredWarnings {
			if warning.ACLID == audit.ACLID && warning.GroupID == audit.GroupID && warning.RiskLevel == audit.RiskLevel {
				continue
			}
			warning.StartedAt, warning.FinishedAt = audit.StartedAt, audit.FinishedAt
			warning.Output, warning.Error = audit.Output+"; policy_warning", audit.Error
			if v.provider.Record(ctx, s.grant, warning) != nil {
				status = "failed"
			}
		}
		end := time.Now()
		v.update(s, e, func(x *ExecutionView) { x.AuditStatus = status; x.FinishedAt = &end })
		if v.journal(s, e, "finished") != nil {
			v.update(s, e, func(x *ExecutionView) { x.AuditStatus = "failed" })
		}
	}()
	failEdit := func(err error) {
		status := "failed"
		if e.ctx.Err() != nil || e.cancelRequested.Load() {
			status = "canceled"
		}
		v.update(s, e, func(x *ExecutionView) { x.Status = status; x.Error = editSafeFailure(err) })
	}
	if err := e.ctx.Err(); err != nil {
		failEdit(editError("edit_canceled", "Save was canceled before execution"))
		return
	}
	if c.lost.Load() || c.closed.Load() {
		failEdit(editError("connection_lost", "Reconnect before saving"))
		return
	}
	if err := s.grant.Check(); err != nil {
		failEdit(editError("session_unavailable", err.Error()))
		return
	}
	s.grant.SetBusy(true)
	defer s.grant.SetBusy(false)
	if v.journal(s, e, "accepted") != nil {
		failEdit(editError("audit_persistence_failed", "Save intent could not be recorded; no changes were sent"))
		return
	}
	policy := []string{}
	for _, command := range e.edit.commands {
		policy = append(policy, command.policySQL)
	}
	commands := append([]string{strings.Join(policy, ";\n")}, policy...)
	if err := v.authorizeExecution(s, e, commands, &audit); err != nil {
		var pe *jumpserver.PolicyError
		if errors.As(err, &pe) {
			audit.RiskLevel, audit.ACLID, audit.GroupID = pe.RiskLevel, pe.ACLID, pe.GroupID
			if pe.Code == "command_warning_confirmation_required" {
				audit.RiskLevel = 0
			}
			failEdit(editError(pe.Code, pe.Error()))
		} else {
			failEdit(editError("command_denied", "Saving is not permitted"))
		}
		return
	}
	if e.ctx.Err() != nil || e.cancelRequested.Load() {
		failEdit(editError("edit_canceled", "Save was canceled before execution"))
		return
	}
	p := e.edit
	s.mu.Lock()
	result := s.results[p.resultID]
	unavailable := p.used || time.Now().After(p.expires) || result == nil || result.editStale || s.closed
	if !unavailable {
		p.used = true
	}
	s.mu.Unlock()
	if unavailable {
		failEdit(editError("edit_plan_expired", "Refresh this result and create a new preview"))
		return
	}
	if p.source == "csv" && !s.grant.Permissions.Upload {
		failEdit(editError("upload_permission_denied", "CSV import is not permitted"))
		return
	}
	// Revalidate table metadata immediately before touching data. Existing
	// previews cannot outlive changed PK/type/generated-column definitions.
	ctx, stop := context.WithTimeout(e.ctx, 5*time.Second)
	resolved, resolveErr := v.resolveEdit(ctx, s, c, result)
	stop()
	if resolveErr != nil || resolved == nil || editSchemaFingerprint(resolved) != p.schemaFingerprint {
		failEdit(editError("edit_schema_changed", "Table metadata changed; refresh and preview the changes again"))
		return
	}
	started := time.Now()
	v.update(s, e, func(x *ExecutionView) { x.Status = "running"; x.StartedAt = &started })
	boundary, err := beginEditBoundary(e.ctx, c, s.grant.Config.Dialect)
	if err != nil {
		failEdit(err)
		return
	}
	touched.Store(true)
	rollback := func(cause error) {
		cleanup, done := context.WithTimeout(context.Background(), 5*time.Second)
		rollbackErr := boundary.rollback(cleanup)
		done()
		if rollbackErr != nil {
			invalidateEditConnection(s, c)
			v.update(s, e, func(x *ExecutionView) {
				x.Status = "unknown"
				x.Error = editError("edit_rollback_unknown", "Rollback could not be confirmed. Reconnect and verify the data before repeating this save")
				x.Edit.Outcome = "unknown"
				x.Edit.RefreshRequired = true
			})
			return
		}
		v.update(s, e, func(x *ExecutionView) { x.Edit.Outcome = "rolled_back" })
		failEdit(cause)
	}
	for _, command := range p.commands {
		if e.ctx.Err() != nil || e.cancelRequested.Load() {
			rollback(editError("edit_canceled", "Save was canceled and its changes were rolled back"))
			return
		}
		if err := s.grant.Check(); err != nil {
			rollback(editError("session_unavailable", "Session changed; this save was rolled back"))
			return
		}
		for _, key := range []string{commands[0], command.policySQL} {
			if warning, ok := e.policyWarnings[key]; ok {
				warningKey := warning.ACLID + ":" + warning.GroupID
				triggeredWarnings[warningKey] = jumpserver.AuditRecord{ID: e.view.ID + ":warning:" + warningKey, SQL: e.sql, RiskLevel: warning.RiskLevel, ACLID: warning.ACLID, GroupID: warning.GroupID}
				if warning.RiskLevel > audit.RiskLevel {
					audit.RiskLevel, audit.ACLID, audit.GroupID = warning.RiskLevel, warning.ACLID, warning.GroupID
				}
			}
		}
		result, execErr := boundary.exec(e.ctx, command.sql, command.args...)
		if execErr != nil {
			rollback(execErr)
			return
		}
		count, countErr := result.RowsAffected()
		if countErr != nil || count != 1 {
			rollback(editError("edit_conflict", "A row changed or disappeared since it was loaded. This entire save was rolled back; refresh the table"))
			return
		}
	}
	if e.ctx.Err() != nil || e.cancelRequested.Load() {
		rollback(editError("edit_canceled", "Save was canceled and its changes were rolled back"))
		return
	}
	if err := s.grant.Check(); err != nil {
		rollback(editError("session_unavailable", "Session changed; this save was rolled back"))
		return
	}
	if err := boundary.finish(e.ctx); err != nil {
		if boundary.tx == nil {
			rollback(err)
			return
		}
		invalidateEditConnection(s, c)
		s.mu.Lock()
		result.editStale = true
		s.mu.Unlock()
		v.update(s, e, func(x *ExecutionView) {
			x.Status = "unknown"
			x.Error = editError("edit_commit_unknown", "Commit was sent but not confirmed. Verify the data before repeating this save")
			x.Edit.Outcome = "unknown"
			x.Edit.RefreshRequired = true
		})
		return
	}
	s.mu.Lock()
	result.editStale = true
	s.mu.Unlock()
	v.update(s, e, func(x *ExecutionView) {
		x.Status = "succeeded"
		x.Edit.ChangesApplied = true
		x.Edit.DatabaseCommitted = boundary.tx != nil
		x.Edit.AffectedRows = p.count
		x.Edit.RefreshRequired = true
		x.Edit.Outcome = "staged"
		if boundary.tx != nil {
			x.Edit.Outcome = "committed"
		}
		x.StatementResults = append(x.StatementResults, StatementResult{RowsAffected: fmt.Sprint(p.count)})
	})
}
