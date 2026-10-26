package jumpserver

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
	"google.golang.org/protobuf/proto"
)

// Use generated protobuf clients over a real gRPC transport. The fake endpoint
// implements only the Core-facing ticket state machine, not client marshalling.
type reviewWireWisp struct {
	*wireWisp
	mu                      sync.Mutex
	created                 []*protobuf.CommandConfirmRequest
	checks, cancels         []*protobuf.TicketRequest
	states                  []protobuf.TicketState_State
	checkFails, cancelFails bool
	createGate              <-chan struct{}
	checkGate               <-chan struct{}
	createStarted           chan struct{}
	onCheck                 func()
}

func reviewInfo() *protobuf.TicketInfo {
	return &protobuf.TicketInfo{
		CheckReq:        &protobuf.ReqInfo{Method: "GET", Url: "/api/v1/tickets/super-tickets/synthetic/status/"},
		CancelReq:       &protobuf.ReqInfo{Method: "DELETE", Url: "/api/v1/tickets/super-tickets/synthetic/status/"},
		TicketDetailUrl: "https://core.example.invalid/ui/#/tickets/synthetic?type=command_confirm",
		Reviewers:       []string{"Reviewer"},
	}
}

func (w *reviewWireWisp) CreateCommandTicket(ctx context.Context, request *protobuf.CommandConfirmRequest) (*protobuf.CommandConfirmResponse, error) {
	w.mu.Lock()
	w.created = append(w.created, proto.Clone(request).(*protobuf.CommandConfirmRequest))
	w.mu.Unlock()
	if w.createStarted != nil {
		w.createStarted <- struct{}{}
	}
	if w.createGate != nil {
		select {
		case <-w.createGate:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return &protobuf.CommandConfirmResponse{Status: &protobuf.Status{Ok: true}, Info: reviewInfo()}, nil
}

func (w *reviewWireWisp) CheckTicketState(ctx context.Context, request *protobuf.TicketRequest) (*protobuf.TicketStateResponse, error) {
	w.mu.Lock()
	w.checks = append(w.checks, proto.Clone(request).(*protobuf.TicketRequest))
	fails := w.checkFails
	state := protobuf.TicketState_Open
	if len(w.states) > 0 {
		state = w.states[0]
		if len(w.states) > 1 {
			w.states = w.states[1:]
		}
	}
	w.mu.Unlock()
	if w.onCheck != nil {
		w.onCheck()
	}
	if w.checkGate != nil {
		select {
		case <-w.checkGate:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return &protobuf.TicketStateResponse{Status: &protobuf.Status{Ok: !fails}, Data: &protobuf.TicketState{State: state, Processor: "Reviewer"}}, nil
}

func (w *reviewWireWisp) CancelTicket(_ context.Context, request *protobuf.TicketRequest) (*protobuf.StatusResponse, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.cancels = append(w.cancels, proto.Clone(request).(*protobuf.TicketRequest))
	return &protobuf.StatusResponse{Status: &protobuf.Status{Ok: !w.cancelFails}}, nil
}

func reviewGRPCProvider(t *testing.T, w *reviewWireWisp) (*WispProvider, *Grant) {
	t.Helper()
	backend := &testWisp{token: validToken()}
	backend.token.FilterRules = []*protobuf.CommandACL{{Id: "review-acl", Action: protobuf.CommandACL_Review, IsActive: true, CommandGroups: []*protobuf.CommandGroup{{Id: "review-group", Pattern: `(?i)\bselect\b`}}}}
	w.wireWisp = &wireWisp{backend: backend, tasks: make(chan *protobuf.TerminalTask), acks: make(chan string)}
	listener := bufconn.Listen(1 << 20)
	server := grpc.NewServer()
	protobuf.RegisterServiceServer(server, w)
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(server.Stop)
	connection, err := grpc.NewClient("passthrough:///review-test", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) { return listener.DialContext(ctx) }))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = connection.Close() })
	p, err := NewWispProvider(protobuf.NewServiceClient(connection), Options{SpoolDir: t.TempDir(), RequestTimeout: time.Second, ReviewTimeout: time.Second, ReviewPollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	return p, g
}

func TestReviewGRPCApprovalBindsFullBatchAndAudit(t *testing.T) {
	w := &reviewWireWisp{states: []protobuf.TicketState_State{protobuf.TicketState_Open, protobuf.TicketState_Approved}}
	p, g := reviewGRPCProvider(t, w)
	batch := "SELECT '审批 😀';\nSELECT 2;"
	var updates []ReviewStatus
	decision, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 2;", BatchSQL: batch, SubmitReview: true, OnReview: func(status ReviewStatus) { updates = append(updates, status) }})
	if err != nil || decision.RiskLevel != int32(protobuf.RiskLevel_ReviewAccept) || decision.ACLID != "review-acl" || decision.GroupID != "review-group" {
		t.Fatalf("approval decision: %+v %v", decision, err)
	}
	if len(updates) != 2 || updates[0].State != "pending" || updates[1].State != "approved" || updates[1].Processor != "Reviewer" || updates[0].TicketURL != reviewInfo().TicketDetailUrl || len(updates[0].Reviewers) != 1 {
		t.Fatalf("review status snapshots: %+v", updates)
	}
	w.mu.Lock()
	if len(w.created) != 1 || w.created[0].Cmd != batch || w.created[0].SessionId != g.ID || w.created[0].CmdAclId != "review-acl" || len(w.checks) != 2 || !proto.Equal(w.checks[0].Req, reviewInfo().CheckReq) || len(w.cancels) != 0 {
		t.Fatal("ticket identity/full immutable batch/RPC lifecycle changed")
	}
	w.mu.Unlock()
	if err := p.Record(context.Background(), g, AuditRecord{SQL: batch, RiskLevel: decision.RiskLevel, ACLID: decision.ACLID, GroupID: decision.GroupID}); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	w.backend.mu.Lock()
	defer w.backend.mu.Unlock()
	if len(w.backend.commands) != 1 || w.backend.commands[0].RiskLevel != protobuf.RiskLevel_ReviewAccept {
		t.Fatal("approved command misclassified as rejected")
	}
}

func TestReviewExplicitConsentAndCoreCharacterLimit(t *testing.T) {
	w := &reviewWireWisp{states: []protobuf.TicketState_State{protobuf.TicketState_Approved}}
	p, g := reviewGRPCProvider(t, w)
	_, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1"})
	var policy *PolicyError
	if !errors.As(err, &policy) || policy.Code != "command_review_required" {
		t.Fatalf("missing explicit consent requirement: %v", err)
	}
	_, err = p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", BatchSQL: strings.Repeat("中", MaxReviewCharacters+1), SubmitReview: true})
	if !errors.As(err, &policy) || policy.Code != "command_review_sql_too_long" {
		t.Fatalf("truncated approval was possible: %v", err)
	}
	w.mu.Lock()
	if len(w.created) != 0 {
		t.Fatal("ticket created before explicit valid submission")
	}
	w.mu.Unlock()
	_, err = p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", BatchSQL: strings.Repeat("中", MaxReviewCharacters), SubmitReview: true})
	if err != nil {
		t.Fatalf("Core limit counts Unicode characters, not UTF8 bytes: %v", err)
	}
	// Approval is never cached on the Grant or reusable by an edited command.
	_, err = p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 2"})
	if !errors.As(err, &policy) || policy.Code != "command_review_required" {
		t.Fatal("approval escaped its invocation")
	}
}

func TestReviewGRPCTerminalStatesAndUnknownStateFailClosed(t *testing.T) {
	for _, tc := range []struct {
		state      protobuf.TicketState_State
		code, view string
		risk       protobuf.RiskLevel
		cancel     bool
	}{
		{protobuf.TicketState_Rejected, "command_review_rejected", "rejected", protobuf.RiskLevel_ReviewReject, false},
		{protobuf.TicketState_Closed, "command_review_canceled", "canceled", protobuf.RiskLevel_ReviewCancel, false},
		{protobuf.TicketState_State(999), "command_review_failed", "failed", protobuf.RiskLevel_ReviewReject, true},
	} {
		t.Run(tc.view+tc.state.String(), func(t *testing.T) {
			w := &reviewWireWisp{states: []protobuf.TicketState_State{tc.state}}
			p, g := reviewGRPCProvider(t, w)
			var latest ReviewStatus
			decision, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true, OnReview: func(s ReviewStatus) { latest = s }})
			var policy *PolicyError
			if !errors.As(err, &policy) || policy.Code != tc.code || decision.RiskLevel != int32(tc.risk) || latest.State != tc.view {
				t.Fatalf("wrong terminal state %+v %+v %v", decision, latest, err)
			}
			p.flush(context.Background())
			w.mu.Lock()
			defer w.mu.Unlock()
			if (len(w.cancels) > 0) != tc.cancel {
				t.Fatal("wrong terminal-ticket cancellation")
			}
		})
	}
}

func TestReviewCancelPersistsAndRetriesOverGRPC(t *testing.T) {
	w := &reviewWireWisp{cancelFails: true}
	p, g := reviewGRPCProvider(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	_, err := p.AuthorizeCommand(ctx, g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true, OnReview: func(s ReviewStatus) {
		if s.State == "pending" {
			cancel()
		}
	}})
	var policy *PolicyError
	if !errors.As(err, &policy) || policy.Code != "command_review_canceled" || policy.RiskLevel != int32(protobuf.RiskLevel_ReviewCancel) {
		t.Fatalf("cancellation decision %v", err)
	}
	p.flush(context.Background())
	files, _ := filepath.Glob(filepath.Join(p.spool.dir, "*.json"))
	if len(files) != 1 {
		t.Fatal("failed remote cancellation was not durable")
	}
	w.mu.Lock()
	w.cancelFails = false
	w.mu.Unlock()
	p.flush(context.Background())
	files, _ = filepath.Glob(filepath.Join(p.spool.dir, "*.json"))
	if len(files) != 0 {
		t.Fatal("ticket cancellation did not recover")
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.cancels) != 2 || !proto.Equal(w.cancels[1].Req, reviewInfo().CancelReq) {
		t.Fatal("wrong cancellation request across retry")
	}
}

func TestReviewTimeoutAndConcurrentSessionLock(t *testing.T) {
	t.Run("timeout", func(t *testing.T) {
		w := &reviewWireWisp{}
		p, g := reviewGRPCProvider(t, w)
		p.options.ReviewTimeout = 15 * time.Millisecond
		_, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true})
		var policy *PolicyError
		if !errors.As(err, &policy) || policy.Code != "command_review_timed_out" {
			t.Fatal(err)
		}
		p.flush(context.Background())
		w.mu.Lock()
		defer w.mu.Unlock()
		if len(w.cancels) != 1 {
			t.Fatal("expired ticket not canceled")
		}
	})
	t.Run("lock-during-approval", func(t *testing.T) {
		w := &reviewWireWisp{states: []protobuf.TicketState_State{protobuf.TicketState_Approved}}
		p, g := reviewGRPCProvider(t, w)
		w.onCheck = func() { p.applyTask(&protobuf.TerminalTask{SessionId: g.ID, Action: protobuf.TaskAction_LockSession}) }
		_, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true})
		var policy *PolicyError
		if !errors.As(err, &policy) || policy.Code != ErrLocked.Error() {
			t.Fatalf("approved ticket bypassed concurrent lock: %v", err)
		}
	})
}

func TestReviewDeadlineBoundaryBeforeContextTimerRuns(t *testing.T) {
	deadline := time.Now().Add(time.Hour)
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	for _, tc := range []struct {
		name string
		now  time.Time
		want error
	}{
		{"before", deadline.Add(-time.Nanosecond), nil},
		{"at", deadline, context.DeadlineExceeded},
		{"after", deadline.Add(time.Nanosecond), context.DeadlineExceeded},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if ctx.Err() != nil {
				t.Fatal("test must keep the context timer pending")
			}
			if err := reviewContextError(ctx, tc.now); !errors.Is(err, tc.want) {
				t.Fatalf("deadline boundary error = %v, want %v", err, tc.want)
			}
		})
	}
	cancel()
	if err := reviewContextError(ctx, deadline); !errors.Is(err, context.Canceled) {
		t.Fatal("explicit cancellation lost precedence")
	}
}

func TestReviewEarlyRPCTimeoutDoesNotExpireWholeReview(t *testing.T) {
	w := &reviewWireWisp{checkGate: make(chan struct{}), states: []protobuf.TicketState_State{protobuf.TicketState_Approved}}
	p, g := reviewGRPCProvider(t, w)
	p.options.ReviewTimeout = time.Minute
	var latest ReviewStatus
	decision, err := p.AuthorizeCommand(context.Background(), g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true, OnReview: func(s ReviewStatus) {
		latest = s
		if s.State == "pending" {
			// Creation has finished; only the check RPC gets this short deadline.
			p.options.RequestTimeout = 5 * time.Millisecond
		}
	}})
	var policy *PolicyError
	if !errors.As(err, &policy) || policy.Code != "command_review_failed" || latest.State != "failed" || decision.RiskLevel != int32(protobuf.RiskLevel_ReviewReject) {
		t.Fatalf("early RPC failure misclassified as review expiry/approval: %+v %v", latest, err)
	}
	p.options.RequestTimeout = time.Second
	p.flush(context.Background())
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.checks) != 1 || len(w.cancels) != 1 {
		t.Fatal("timed out check did not persist and deliver ticket cancellation")
	}
}

func TestReviewLateCreateAfterCancelCompensates(t *testing.T) {
	gate := make(chan struct{})
	w := &reviewWireWisp{createGate: gate, createStarted: make(chan struct{}, 1)}
	p, g := reviewGRPCProvider(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := p.AuthorizeCommand(ctx, g, AuthorizationRequest{SQL: "SELECT 1", SubmitReview: true})
		done <- err
	}()
	<-w.createStarted
	cancel()
	if err := <-done; err == nil {
		t.Fatal("canceled creation approved")
	}
	close(gate)
	eventually(t, func() bool {
		files, _ := os.ReadDir(p.spool.dir)
		for _, file := range files {
			if strings.HasSuffix(file.Name(), ".json") {
				return true
			}
		}
		return false
	})
	p.flush(context.Background())
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.cancels) != 1 {
		t.Fatal("late created ticket leaked")
	}
}

func TestReviewTicketURLRejectsExecutableSchemes(t *testing.T) {
	for _, value := range []string{"javascript:alert(1)", "data:text/html,x", "//other.invalid/ticket", "https://name:secret@example.invalid/ticket"} {
		if safeTicketURL(value) != "" {
			t.Fatal("unsafe ticket link accepted")
		}
	}
	if safeTicketURL("/ui/#/tickets/id") != "/ui/#/tickets/id" {
		t.Fatal("relative Core ticket link lost")
	}
}
