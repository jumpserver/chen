package jumpserver

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

// Exercise generated Wisp clients, protobuf marshalling and the bidirectional
// control stream, in addition to the fault-injection tests using direct fakes.
type wireWisp struct {
	protobuf.UnimplementedServiceServer
	backend *testWisp
	tasks   chan *protobuf.TerminalTask
	acks    chan string
}

type reconnectingWireWisp struct {
	*wireWisp
	mu        sync.Mutex
	streams   int
	started   chan int
	dropFirst chan struct{}
}

func (w *reconnectingWireWisp) DispatchTask(stream protobuf.Service_DispatchTaskServer) error {
	w.mu.Lock()
	w.streams++
	attempt := w.streams
	w.mu.Unlock()
	w.started <- attempt
	if attempt == 1 {
		select {
		case <-stream.Context().Done():
			return stream.Context().Err()
		case <-w.dropFirst:
			return status.Error(codes.Unavailable, "synthetic control channel interruption")
		}
	}
	return w.wireWisp.DispatchTask(stream)
}

func TestWispGRPCControlLossPausesAndRecoveryPreservesAdminLock(t *testing.T) {
	for _, adminLock := range []bool{false, true} {
		t.Run(map[bool]string{false: "resume", true: "preserve-admin-lock"}[adminLock], func(t *testing.T) {
			listener := bufconn.Listen(1 << 20)
			server := grpc.NewServer()
			w := &reconnectingWireWisp{wireWisp: &wireWisp{backend: &testWisp{token: validToken()}, tasks: make(chan *protobuf.TerminalTask), acks: make(chan string)}, started: make(chan int, 4), dropFirst: make(chan struct{})}
			protobuf.RegisterServiceServer(server, w)
			go func() { _ = server.Serve(listener) }()
			t.Cleanup(server.Stop)
			connection, err := grpc.NewClient("passthrough:///control-recovery-test", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) { return listener.DialContext(ctx) }))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = connection.Close() })
			p, err := NewWispProvider(protobuf.NewServiceClient(connection), Options{SpoolDir: t.TempDir(), RetryInterval: 80 * time.Millisecond, CheckInterval: time.Millisecond})
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			done := make(chan struct{})
			go func() { p.Run(ctx); close(done) }()
			select {
			case <-w.started:
			case <-ctx.Done():
				t.Fatal("first stream absent")
			}
			g, err := p.Open(ctx, "synthetic-token", "192.0.2.1")
			if err != nil {
				t.Fatal(err)
			}
			if err := p.Connected(ctx, g); err != nil {
				t.Fatal(err)
			}
			controls := make(chan Control, 8)
			go p.Watch(ctx, g, func(c Control) { controls <- c })
			close(w.dropFirst)
			select {
			case control := <-controls:
				if control.Type != "lock" || control.Reason != ErrControlUnavailable.Error() {
					t.Fatalf("lost control did not pause: %+v", control)
				}
			case <-ctx.Done():
				t.Fatal("missing lost-control event")
			}
			if err := g.Check(); !errors.Is(err, ErrControlUnavailable) {
				t.Fatalf("lost control allowed operations: %v", err)
			}
			if _, err := p.Open(ctx, "another-token", "192.0.2.2"); !errors.Is(err, ErrControlNotReady) {
				t.Fatal("new sessions allowed without controls")
			}
			if adminLock {
				p.applyTask(&protobuf.TerminalTask{SessionId: g.ID, Action: protobuf.TaskAction_LockSession})
			}
			select {
			case <-w.started:
			case <-ctx.Done():
				t.Fatal("control stream did not reconnect")
			}
			eventually(t, func() bool { g.mu.Lock(); defer g.mu.Unlock(); return !g.controlUnavailable })
			if adminLock {
				if !errors.Is(g.Check(), ErrLocked) {
					t.Fatal("reconnect incorrectly removed administrator lock")
				}
				p.applyTask(&protobuf.TerminalTask{SessionId: g.ID, Action: protobuf.TaskAction_UnlockSession})
			}
			if err := g.Check(); err != nil {
				t.Fatalf("recovered session remained unusable: %v", err)
			}
			cancel()
			<-done
		})
	}
}

func (w *wireWisp) GetTokenAuthInfo(ctx context.Context, r *protobuf.TokenRequest) (*protobuf.TokenResponse, error) {
	return w.backend.GetTokenAuthInfo(ctx, r)
}
func (w *wireWisp) CreateSession(ctx context.Context, r *protobuf.SessionCreateRequest) (*protobuf.SessionCreateResponse, error) {
	return w.backend.CreateSession(ctx, r)
}
func (w *wireWisp) FinishSession(ctx context.Context, r *protobuf.SessionFinishRequest) (*protobuf.SessionFinishResp, error) {
	return w.backend.FinishSession(ctx, r)
}
func (w *wireWisp) UploadCommand(ctx context.Context, r *protobuf.CommandRequest) (*protobuf.CommandResponse, error) {
	return w.backend.UploadCommand(ctx, r)
}
func (w *wireWisp) RecordSessionLifecycleLog(ctx context.Context, r *protobuf.SessionLifecycleLogRequest) (*protobuf.StatusResponse, error) {
	return w.backend.RecordSessionLifecycleLog(ctx, r)
}
func (w *wireWisp) DispatchTask(stream protobuf.Service_DispatchTaskServer) error {
	for {
		select {
		case <-stream.Context().Done():
			return stream.Context().Err()
		case task := <-w.tasks:
			if err := stream.Send(&protobuf.TaskResponse{Task: task}); err != nil {
				return err
			}
			ack, err := stream.Recv()
			if err != nil {
				return err
			}
			w.acks <- ack.TaskId
		}
	}
}

func TestWispGRPCControlAndAuditRoundTrip(t *testing.T) {
	listener := bufconn.Listen(1 << 20)
	server := grpc.NewServer()
	w := &wireWisp{backend: &testWisp{token: validToken()}, tasks: make(chan *protobuf.TerminalTask, 1), acks: make(chan string, 1)}
	w.backend.token.FilterRules = []*protobuf.CommandACL{{Id: "notification-acl", Action: protobuf.CommandACL_NotifyWarning, IsActive: true, CommandGroups: []*protobuf.CommandGroup{{Id: "notification-group", Pattern: "select"}}}}
	protobuf.RegisterServiceServer(server, w)
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(server.Stop)
	connection, err := grpc.NewClient("passthrough:///wisp-test", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) { return listener.DialContext(ctx) }))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = connection.Close() })
	p, err := NewWispProvider(protobuf.NewServiceClient(connection), Options{SpoolDir: t.TempDir(), RetryInterval: time.Millisecond, CheckInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() { p.Run(ctx); close(done) }()
	eventually(t, func() bool { p.mu.RLock(); defer p.mu.RUnlock(); return p.controlAvailable })
	g, err := p.Open(ctx, "synthetic-connect-token", "192.0.2.1:45678")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Connected(ctx, g); err != nil {
		t.Fatal(err)
	}
	if err := p.Authorize(ctx, g, "select 1"); err == nil {
		t.Fatal("NotifyWarning must require explicit confirmation")
	}
	if _, err := p.AuthorizeCommand(ctx, g, AuthorizationRequest{SQL: "select 1", ConfirmWarnings: true}); err != nil {
		t.Fatal("confirmed NotifyWarning failed:", err)
	}
	if err := p.Record(ctx, g, AuditRecord{SQL: "select 1", Output: "rows=1"}); err != nil {
		t.Fatal(err)
	}
	controls := make(chan Control, 4)
	go p.Watch(ctx, g, func(control Control) {
		controls <- control
		if control.Type == "terminate" {
			_ = p.Close(context.Background(), g)
		}
	})
	for _, tc := range []struct {
		action protobuf.TaskAction
		kind   string
	}{
		{protobuf.TaskAction_LockSession, "lock"},
		{protobuf.TaskAction_UnlockSession, "unlock"},
		{protobuf.TaskAction_KillSession, "terminate"},
	} {
		w.tasks <- &protobuf.TerminalTask{Id: tc.kind + "-id", SessionId: g.ID, Action: tc.action, TerminatedBy: "admin"}
		select {
		case control := <-controls:
			if control.Type != tc.kind {
				t.Fatalf("control = %s, want %s", control.Type, tc.kind)
			}
		case <-ctx.Done():
			t.Fatal("control stream timed out")
		}
		select {
		case ack := <-w.acks:
			if ack != tc.kind+"-id" {
				t.Fatalf("wrong task acknowledgement: %s", ack)
			}
		case <-ctx.Done():
			t.Fatal("task acknowledgement timed out")
		}
	}
	eventually(t, func() bool {
		w.backend.mu.Lock()
		defer w.backend.mu.Unlock()
		return len(w.backend.finishes) == 1 && len(w.backend.commands) == 1 && len(w.backend.lifecycles) == 2
	})
	w.backend.mu.Lock()
	if w.backend.commands[0].RiskLevel != protobuf.RiskLevel_Warning || w.backend.commands[0].CmdAclId != "notification-acl" || w.backend.commands[0].CmdGroupId != "notification-group" {
		t.Fatal("Wisp/Core notification lost its risk or ACL recipient identity")
	}
	w.backend.mu.Unlock()
	cancel()
	<-done
}
