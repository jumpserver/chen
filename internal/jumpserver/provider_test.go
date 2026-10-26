package jumpserver

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/proto"
)

type testWisp struct {
	protobuf.ServiceClient
	mu            sync.Mutex
	token         *protobuf.TokenAuthInfo
	commands      []*protobuf.CommandRequest
	lifecycles    []*protobuf.SessionLifecycleLogRequest
	finishes      []*protobuf.SessionFinishRequest
	deletes       []string
	creates       int
	uploadFail    bool
	finishFail    bool
	forward       *protobuf.ForwardResponse
	forwardCalled chan struct{}
	forwardReturn chan struct{}
}

func (w *testWisp) GetTokenAuthInfo(context.Context, *protobuf.TokenRequest, ...grpc.CallOption) (*protobuf.TokenResponse, error) {
	return &protobuf.TokenResponse{Status: &protobuf.Status{Ok: true}, Data: w.token}, nil
}

func (w *testWisp) CreateSession(_ context.Context, req *protobuf.SessionCreateRequest, _ ...grpc.CallOption) (*protobuf.SessionCreateResponse, error) {
	w.mu.Lock()
	w.creates++
	w.mu.Unlock()
	return &protobuf.SessionCreateResponse{Status: &protobuf.Status{Ok: true}, Data: proto.Clone(req.Data).(*protobuf.Session)}, nil
}

func (w *testWisp) CreateForward(ctx context.Context, _ *protobuf.ForwardRequest, _ ...grpc.CallOption) (*protobuf.ForwardResponse, error) {
	if w.forwardCalled != nil {
		close(w.forwardCalled)
	}
	if w.forwardReturn != nil {
		select {
		case <-w.forwardReturn:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return w.forward, nil
}

func (w *testWisp) DeleteForward(_ context.Context, req *protobuf.ForwardDeleteRequest, _ ...grpc.CallOption) (*protobuf.StatusResponse, error) {
	w.mu.Lock()
	w.deletes = append(w.deletes, req.Id)
	w.mu.Unlock()
	return &protobuf.StatusResponse{Status: &protobuf.Status{Ok: true}}, nil
}

func (w *testWisp) FinishSession(_ context.Context, req *protobuf.SessionFinishRequest, _ ...grpc.CallOption) (*protobuf.SessionFinishResp, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.finishFail {
		return nil, errors.New("unavailable")
	}
	w.finishes = append(w.finishes, proto.Clone(req).(*protobuf.SessionFinishRequest))
	return &protobuf.SessionFinishResp{Status: &protobuf.Status{Ok: true}}, nil
}

func (w *testWisp) UploadCommand(_ context.Context, req *protobuf.CommandRequest, _ ...grpc.CallOption) (*protobuf.CommandResponse, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.uploadFail {
		return nil, errors.New("unavailable")
	}
	w.commands = append(w.commands, proto.Clone(req).(*protobuf.CommandRequest))
	return &protobuf.CommandResponse{Status: &protobuf.Status{Ok: true}}, nil
}

func (w *testWisp) RecordSessionLifecycleLog(_ context.Context, req *protobuf.SessionLifecycleLogRequest, _ ...grpc.CallOption) (*protobuf.StatusResponse, error) {
	w.mu.Lock()
	w.lifecycles = append(w.lifecycles, proto.Clone(req).(*protobuf.SessionLifecycleLogRequest))
	w.mu.Unlock()
	return &protobuf.StatusResponse{Status: &protobuf.Status{Ok: true}}, nil
}

func (w *testWisp) DispatchTask(ctx context.Context, _ ...grpc.CallOption) (protobuf.Service_DispatchTaskClient, error) {
	<-ctx.Done()
	return nil, ctx.Err()
}

func validToken() *protobuf.TokenAuthInfo {
	return &protobuf.TokenAuthInfo{
		KeyId: "test-token-id", User: &protobuf.User{Id: "user-id", Name: "Test User", Username: "tester", IsActive: true, IsValid: true},
		Asset: &protobuf.Asset{Id: "asset-id", Name: "Test DB", OrgId: "org-id", Address: "db.example.invalid",
			Protocols: []*protobuf.Protocol{{Name: "mysql", Port: 3306}}, Specific: &protobuf.Asset_Specific{DbName: "app"}},
		Account:    &protobuf.Account{Id: "account-id", Name: "Test Account", Username: "dbuser", Secret: "test-secret", SecretType: &protobuf.LabelValue{Value: "password"}},
		Permission: &protobuf.Permission{EnableConnect: true, EnableCopy: true},
		ExpireInfo: &protobuf.ExpireInfo{ExpireAt: time.Now().Add(time.Hour).Unix()},
		Setting:    &protobuf.ComponentSetting{MaxIdleTime: 5, MaxSessionTime: 8},
	}
}

func testProvider(t *testing.T, w *testWisp) *WispProvider {
	t.Helper()
	p, err := NewWispProvider(w, Options{SpoolDir: t.TempDir(), RequestTimeout: time.Second, CheckInterval: 5 * time.Millisecond, RetryInterval: 10 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestOpenRequiresCorePermissionAndSupportedPolicy(t *testing.T) {
	tests := []struct {
		name string
		edit func(*protobuf.TokenAuthInfo)
		want string
	}{
		{"denied connect", func(info *protobuf.TokenAuthInfo) { info.Permission.EnableConnect = false }, "connect_permission_denied"},
		{"expired", func(info *protobuf.TokenAuthInfo) { info.ExpireInfo.ExpireAt = time.Now().Unix() - 1 }, "permission_expired"},
		{"not password", func(info *protobuf.TokenAuthInfo) { info.Account.SecretType.Value = "ssh_key" }, "unsupported_database_credential_type"},
		{"unsupported db", func(info *protobuf.TokenAuthInfo) { info.Asset.Protocols[0].Name = "redis" }, "unsupported_database_for_go_v1"},
		{"missing settings", func(info *protobuf.TokenAuthInfo) { info.Setting = nil }, "incomplete_connection_authorization"},
		{"java lookaround", func(info *protobuf.TokenAuthInfo) {
			info.FilterRules = []*protobuf.CommandACL{{Id: "deny-rule", Action: protobuf.CommandACL_Reject, CommandGroups: []*protobuf.CommandGroup{{Pattern: "(?=drop)"}}}}
		}, "unsupported_command_acl_regex_use_legacy_chen"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			w := &testWisp{token: validToken()}
			tc.edit(w.token)
			p := testProvider(t, w)
			_, err := p.Open(context.Background(), "synthetic-connect-token", "192.0.2.1:45678")
			if err == nil || err.Error() != tc.want {
				t.Fatalf("Open error = %v, want %s", err, tc.want)
			}
			if w.creates != 0 {
				t.Fatal("created Core session before validating policy")
			}
		})
	}
}

func TestOpenMapsTLSGatewayAndKeepsCredentialsServerSide(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "forward-id", Port: 4567, Status: &protobuf.Status{Ok: true}}}
	w.token.Asset.Protocols[0].Name = "postgresql"
	w.token.Asset.Protocols[0].Port = 5432
	w.token.Asset.Specific = &protobuf.Asset_Specific{DbName: "app", UseSsl: true, CaCert: "synthetic-ca", ClientCert: "synthetic-cert", ClientKey: "synthetic-key", PgSslMode: "verify-full"}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway-id"}}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-connect-token", "192.0.2.1:45678")
	if err != nil {
		t.Fatal(err)
	}
	if g.Config.Host != "127.0.0.1" || g.Config.Port != 4567 || g.Config.TLS.ServerName != "db.example.invalid" || g.Config.TLS.Mode != "verify-full" || g.session.RemoteAddr != "192.0.2.1" {
		t.Fatal("gateway/TLS/remote address mapping failed")
	}
	data, err := json.Marshal(g)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"test-secret", "synthetic-connect-token", "synthetic-key", "db.example.invalid", "synthetic-ca"} {
		if strings.Contains(string(data), secret) {
			t.Fatalf("JSON grant leaked server-only field %q", secret)
		}
	}
	if len(w.lifecycles) != 0 {
		t.Fatal("token exchange claimed asset connection success before DB connected")
	}
	if err := p.Connected(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Connected(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	if len(w.lifecycles) != 2 || w.lifecycles[0].Event != protobuf.SessionLifecycleLogRequest_AssetConnectSuccess || w.lifecycles[1].Event != protobuf.SessionLifecycleLogRequest_AssetConnectFinished {
		t.Fatal("expected one success and one finished lifecycle")
	}
	if len(w.finishes) != 1 || !w.finishes[0].Success || len(w.deletes) != 1 || w.deletes[0] != "forward-id" {
		t.Fatal("close did not finish Core session and forward once")
	}
}

func TestOpenInvalidForwardCompensatesCoreSessionAndReturnedID(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "created-but-invalid", Port: 70000, Status: &protobuf.Status{Ok: true}}}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	if _, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1"); err == nil {
		t.Fatal("accepted invalid forward port")
	}
	p.flush(context.Background())
	if len(w.finishes) != 1 || len(w.deletes) != 1 || w.deletes[0] != "created-but-invalid" || len(w.lifecycles) != 0 || len(p.listGrants()) != 0 {
		t.Fatal("failed Open leaked remote resources or claimed connected")
	}
}

func TestOpenCanceledDuringForwardCleansLateResult(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "late-forward", Port: 5678, Status: &protobuf.Status{Ok: true}}, forwardCalled: make(chan struct{}), forwardReturn: make(chan struct{})}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := p.Open(ctx, "synthetic-token", "192.0.2.1"); done <- err }()
	<-w.forwardCalled
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("Open succeeded despite cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("Open did not honor caller cancellation")
	}
	close(w.forwardReturn)
	eventually(t, func() bool {
		p.flush(context.Background())
		w.mu.Lock()
		defer w.mu.Unlock()
		return len(w.finishes) == 1 && len(w.deletes) == 1 && w.deletes[0] == "late-forward"
	})
}

func TestAdminKillDuringOpenDoesNotLeakLateForward(t *testing.T) {
	w := &testWisp{token: validToken(), forward: &protobuf.ForwardResponse{Id: "forward-after-kill", Port: 5678, Status: &protobuf.Status{Ok: true}}, forwardCalled: make(chan struct{}), forwardReturn: make(chan struct{})}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	done := make(chan error, 1)
	go func() { _, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1"); done <- err }()
	<-w.forwardCalled
	grants := p.listGrants()
	if len(grants) != 1 {
		t.Fatal("pending Core session is not available for admin control")
	}
	p.applyTask(&protobuf.TerminalTask{SessionId: grants[0].ID, Action: protobuf.TaskAction_KillSession})
	close(w.forwardReturn)
	if err := <-done; !errors.Is(err, ErrClosed) {
		t.Fatalf("Open returned after admin kill: %v", err)
	}
	p.flush(context.Background())
	if len(w.finishes) != 1 || len(w.deletes) != 1 || w.deletes[0] != "forward-after-kill" {
		t.Fatal("admin kill racing Open leaked a remote session/forward")
	}
}

func TestAuditOutageSurvivesProviderRestartWithoutFailingSQLResult(t *testing.T) {
	w := &testWisp{token: validToken(), uploadFail: true}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Record(context.Background(), g, AuditRecord{SQL: "UPDATE example SET counter = counter + 1", Output: "affected_rows=1; test-secret"}); err != nil {
		t.Fatalf("persisted SQL result was turned into audit RPC failure: %v", err)
	}
	p.flush(context.Background())
	files, _ := filepath.Glob(filepath.Join(p.spool.dir, "*.json"))
	if len(files) != 1 || len(w.commands) != 0 {
		t.Fatal("audit outage did not retain durable command")
	}
	info, _ := os.Stat(files[0])
	if info.Mode().Perm() != 0600 {
		t.Fatal("audit SQL is readable by other users")
	}
	recovered, err := NewWispProvider(w, Options{SpoolDir: p.spool.dir})
	if err != nil {
		t.Fatal(err)
	}
	w.uploadFail = false
	recovered.flush(context.Background())
	if len(w.commands) != 1 || strings.Contains(w.commands[0].Output, "test-secret") || w.commands[0].Input != "UPDATE example SET counter = counter + 1" {
		t.Fatal("audit recovery lost command identity or leaked credential in status")
	}
	files, _ = filepath.Glob(filepath.Join(p.spool.dir, "*.json"))
	if len(files) != 0 {
		t.Fatal("delivered audit was not removed")
	}
}

func TestAuditDiskFailureIsExplicit(t *testing.T) {
	w := &testWisp{token: validToken()}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(p.spool.dir); err != nil {
		t.Fatal(err)
	}
	if err := p.Record(context.Background(), g, AuditRecord{SQL: "select 1"}); !errors.Is(err, ErrAuditDisk) {
		t.Fatalf("lost audit was not explicit: %v", err)
	}
}

func TestCloseCheckpointDoesNotRepeatSuccessfulForwardCleanup(t *testing.T) {
	w := &testWisp{token: validToken(), finishFail: true, forward: &protobuf.ForwardResponse{Id: "forward", Port: 5678, Status: &protobuf.Status{Ok: true}}}
	w.token.Gateways = []*protobuf.Gateway{{Id: "gateway"}}
	p := testProvider(t, w)
	g, err := p.Open(context.Background(), "synthetic-token", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	w.finishFail = false
	p.flush(context.Background())
	if len(w.deletes) != 1 || len(w.finishes) != 1 {
		t.Fatal("close retry repeated an acknowledged forward delete")
	}
}

func eventually(t *testing.T, predicate func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if predicate() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition did not become true")
}
