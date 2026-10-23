package jumpserver

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

// Run starts exactly one task stream and one outbox/lifecycle worker for this
// provider. Browser sockets have no part in either lifetime.
func (p *WispProvider) Run(ctx context.Context) {
	p.runOnce.Do(func() {
		var workers sync.WaitGroup
		workers.Add(2)
		go func() { defer workers.Done(); p.watchTasks(ctx) }()
		go func() { defer workers.Done(); p.runOutbox(ctx) }()
		p.runMaintenance(ctx)
		workers.Wait()
		shutdown, cancel := context.WithTimeout(context.Background(), p.options.RequestTimeout)
		defer cancel()
		p.flush(shutdown)
	})
}

func (p *WispProvider) runOutbox(ctx context.Context) {
	retry := time.NewTicker(p.options.RetryInterval)
	defer retry.Stop()
	p.flush(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-p.wake:
			p.flush(ctx)
		case <-retry.C:
			p.flush(ctx)
		}
	}
}

func (p *WispProvider) runMaintenance(ctx context.Context) {
	check := time.NewTicker(p.options.CheckInterval)
	defer check.Stop()
	for {
		select {
		case <-ctx.Done():
			for _, grant := range p.listGrants() {
				p.terminate(grant, "server_shutdown", "")
				// Run is stopped after its resource owner has drained. This is a
				// final fallback for callers that violated the owner-close contract.
				if err := p.Close(context.Background(), grant); err != nil {
					p.report("session_cleanup_persistence_failed")
				}
			}
			return
		case <-check.C:
			for _, grant := range p.listGrants() {
				if err := grant.Check(); err != nil && !errors.Is(err, ErrLocked) {
					p.terminate(grant, err.Error(), "")
				}
			}
		}
	}
}

func (p *WispProvider) listGrants() []*Grant {
	p.mu.RLock()
	defer p.mu.RUnlock()
	result := make([]*Grant, 0, len(p.grants))
	for _, grant := range p.grants {
		result = append(result, grant)
	}
	return result
}

func (p *WispProvider) watchTasks(ctx context.Context) {
	for ctx.Err() == nil {
		stream, err := p.client.DispatchTask(ctx)
		if err == nil && stream != nil {
			for ctx.Err() == nil {
				response, recvErr := stream.Recv()
				if recvErr != nil {
					break
				}
				if response == nil || response.Task == nil {
					continue
				}
				p.applyTask(response.Task)
				if response.Task.Action == protobuf.TaskAction_KillSession || response.Task.Action == protobuf.TaskAction_LockSession || response.Task.Action == protobuf.TaskAction_UnlockSession {
					if err := stream.Send(&protobuf.FinishedTaskRequest{TaskId: response.Task.Id}); err != nil {
						break
					}
				}
			}
		}
		if ctx.Err() != nil {
			return
		}
		p.report("session_control_stream_reconnecting")
		if !waitContext(ctx, p.options.RetryInterval) {
			return
		}
	}
}

func waitContext(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (p *WispProvider) applyTask(task *protobuf.TerminalTask) {
	p.mu.RLock()
	grant := p.grants[task.SessionId]
	p.mu.RUnlock()
	if grant == nil {
		return
	}
	if task.Action == protobuf.TaskAction_KillSession {
		p.terminate(grant, "admin_terminate", task.TerminatedBy)
		return
	}
	grant.mu.Lock()
	defer grant.mu.Unlock()
	if grant.closed {
		return
	}
	var control Control
	switch task.Action {
	case protobuf.TaskAction_LockSession:
		grant.locked = true
		control = Control{Type: "lock", Reason: "session_locked", Actor: task.CreatedBy}
	case protobuf.TaskAction_UnlockSession:
		grant.locked = false
		control = Control{Type: "unlock", Actor: task.CreatedBy}
	case protobuf.TaskAction_TokenPermExpired:
		// Match the established Core/Chen 10-minute permission grace period.
		// A repeated notification must not indefinitely extend the deadline.
		if grant.dynamicEnd.IsZero() {
			grant.dynamicEnd = time.Now().Add(10 * time.Minute)
		}
		control = Control{Type: "permission_revoked", Reason: "permission_expires_in_10_minutes"}
	case protobuf.TaskAction_TokenPermValid:
		grant.dynamicEnd = time.Time{}
		control = Control{Type: "permission_restored"}
	default:
		// An unknown admin policy must never silently allow continued access.
		grant.locked = true
		control = Control{Type: "lock", Reason: "unsupported_session_control"}
	}
	notifyLocked(grant, control)
}

func notifyLocked(grant *Grant, control Control) {
	for _, watcher := range grant.watchers {
		select {
		case watcher <- control:
		default:
			// Keep the most recent authoritative state, especially termination.
			select {
			case <-watcher:
			default:
			}
			watcher <- control
		}
	}
}

func (p *WispProvider) terminate(grant *Grant, reason, actor string) {
	grant.mu.Lock()
	if !grant.closed {
		grant.closed = true
		grant.closeReason = reason
		// Connected hands ownership to the engine before it publishes a browser
		// session. This also closes the tiny Connected -> Watch registration gap.
		grant.terminationOwned = grant.connected || len(grant.watchers) > 0
		notifyLocked(grant, Control{Type: "terminate", Reason: reason, Actor: actor})
	}
	owned := grant.terminationOwned
	grant.mu.Unlock()
	if owned {
		return
	}
	if err := p.Close(context.Background(), grant); err != nil {
		p.report("session_cleanup_persistence_failed")
	}
}

// Watch blocks until its caller cancels. It replays current control state so a
// kill/lock arriving between Open and callback registration cannot be missed.
func (p *WispProvider) Watch(ctx context.Context, grant *Grant, callback func(Control)) {
	if grant == nil || callback == nil {
		return
	}
	grant.mu.Lock()
	grant.nextWatcher++
	id := grant.nextWatcher
	channel := make(chan Control, 16)
	grant.watchers[id] = channel
	switch {
	case grant.closed:
		channel <- Control{Type: "terminate", Reason: grant.closeReason}
	case grant.locked:
		channel <- Control{Type: "lock", Reason: "session_locked"}
	case !grant.dynamicEnd.IsZero():
		channel <- Control{Type: "permission_revoked", Reason: "permission_expires_in_10_minutes"}
	}
	grant.mu.Unlock()
	defer func() {
		grant.mu.Lock()
		delete(grant.watchers, id)
		grant.mu.Unlock()
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case control := <-channel:
			callback(control)
			if control.Type == "terminate" {
				return
			}
		}
	}
}
