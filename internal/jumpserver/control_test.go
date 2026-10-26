package jumpserver

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func TestControlLockUnlockRevokeRestoreKillAndLateWatch(t *testing.T) {
	w := &testWisp{token: validToken()}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	apply := func(action protobuf.TaskAction) {
		p.applyTask(&protobuf.TerminalTask{SessionId: g.ID, Id: "task-id", Action: action, CreatedBy: "admin", TerminatedBy: "admin"})
	}
	apply(protobuf.TaskAction_LockSession)
	if err := p.Authorize(context.Background(), g, "select 1"); !errors.Is(err, ErrLocked) {
		t.Fatalf("locked SQL was not denied: %v", err)
	}
	apply(protobuf.TaskAction_UnlockSession)
	if err := g.Check(); err != nil {
		t.Fatal(err)
	}
	apply(protobuf.TaskAction_TokenPermExpired)
	firstDeadline := g.dynamicEnd
	apply(protobuf.TaskAction_TokenPermExpired)
	if g.dynamicEnd != firstDeadline || time.Until(g.dynamicEnd) > 10*time.Minute {
		t.Fatal("repeated revocation extended its grace period")
	}
	apply(protobuf.TaskAction_TokenPermValid)
	if !g.dynamicEnd.IsZero() {
		t.Fatal("restored permission kept revocation deadline")
	}
	apply(protobuf.TaskAction_KillSession)
	if err := g.Check(); !errors.Is(err, ErrClosed) {
		t.Fatalf("killed session stayed active: %v", err)
	}
	// Register only after kill to cover the Open -> Watch race.
	seen := make(chan Control, 1)
	p.Watch(context.Background(), g, func(control Control) { seen <- control })
	if control := <-seen; control.Type != "terminate" || control.Reason != "admin_terminate" {
		t.Fatalf("late subscriber missed authoritative termination: %+v", control)
	}
	p.flush(context.Background())
	if len(w.finishes) != 1 {
		t.Fatal("admin kill did not finish Core session")
	}
}

func TestLockedSessionStillExpiresAndNotifiesEngine(t *testing.T) {
	w := &testWisp{token: validToken()}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	g.mu.Lock()
	g.locked = true
	g.ExpiresAt = time.Now().Add(30 * time.Millisecond)
	g.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	seen := make(chan Control, 8)
	go p.Watch(ctx, g, func(control Control) { seen <- control })
	done := make(chan struct{})
	go func() { p.Run(ctx); close(done) }()
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	for {
		select {
		case control := <-seen:
			if control.Type == "terminate" {
				if control.Reason != "permission_expired" {
					t.Fatalf("expiry reason = %s", control.Reason)
				}
				cancel()
				<-done
				return
			}
		case <-timer.C:
			t.Fatal("locked session did not expire")
		}
	}
}

func TestTerminateOwnerStopsDatabaseBeforeForwardCleanup(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "owned-forward", Port: 5678, Status: &protobuf.Status{Ok: true}}}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	watchDone := make(chan struct{})
	go func() { p.Watch(ctx, g, func(Control) {}); close(watchDone) }()
	eventually(t, func() bool { g.mu.Lock(); defer g.mu.Unlock(); return len(g.watchers) == 1 })
	p.terminate(g, "admin_terminate", "admin")
	<-watchDone
	// Watch has returned, but its owner still needs the forward to send KILL.
	// Repeated expiry scans must not steal cleanup ownership in that window.
	p.terminate(g, "session_closed", "")
	p.flush(context.Background())
	if len(w.finishes) != 0 || len(w.deletes) != 0 {
		t.Fatal("provider removed the forward before the resource owner completed database cancellation")
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	if len(w.finishes) != 1 || len(w.deletes) != 1 {
		t.Fatal("owner completion did not clean up remote resources")
	}
}

func TestConnectedGrantRetainsForwardBeforeWatchRegisters(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "forward-before-watch", Port: 5678, Status: &protobuf.Status{Ok: true}}}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Connected(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.terminate(g, "admin_terminate", "")
	p.flush(context.Background())
	if len(w.finishes) != 0 || len(w.deletes) != 0 {
		t.Fatal("Connected -> Watch registration gap let provider delete an engine-owned forward")
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	if len(w.finishes) != 1 || len(w.deletes) != 1 {
		t.Fatal("engine owner did not release remote resources")
	}
}
