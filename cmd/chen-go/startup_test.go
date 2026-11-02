package main

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	pb "github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
)

type startupProbeClient struct {
	pb.ServiceClient
	probe func(context.Context) (*pb.PublicSettingResponse, error)
}

func (c startupProbeClient) GetPublicSetting(ctx context.Context, _ *pb.Empty, _ ...grpc.CallOption) (*pb.PublicSettingResponse, error) {
	return c.probe(ctx)
}

func TestWaitWispRetriesRPCAndApplicationFailures(t *testing.T) {
	for _, rpcError := range []bool{false, true} {
		calls := 0
		client := startupProbeClient{probe: func(context.Context) (*pb.PublicSettingResponse, error) {
			calls++
			if calls == 1 {
				if rpcError {
					return nil, errors.New("private RPC diagnostic")
				}
				return &pb.PublicSettingResponse{Status: &pb.Status{Err: "private status"}}, nil
			}
			return &pb.PublicSettingResponse{Status: &pb.Status{Ok: true}, Data: &pb.PublicSetting{}}, nil
		}}
		if err := waitWisp(context.Background(), client, 5*time.Second); err != nil {
			t.Fatal(err)
		}
		if calls != 2 {
			t.Fatalf("probe attempts: %d", calls)
		}
	}
}

func TestWaitWispTimeoutAndCancellation(t *testing.T) {
	for _, scenario := range []string{"timeout", "cancel_rpc", "cancel_retry"} {
		t.Run(scenario, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			entered := make(chan struct{})
			client := startupProbeClient{probe: func(ctx context.Context) (*pb.PublicSettingResponse, error) {
				close(entered)
				if scenario != "cancel_retry" {
					<-ctx.Done()
				}
				return nil, errors.New("private RPC diagnostic")
			}}
			done := make(chan error, 1)
			timeout := 5 * time.Second
			if scenario == "timeout" {
				timeout = 50 * time.Millisecond
			}
			go func() { done <- waitWisp(ctx, client, timeout) }()
			<-entered
			want := "wisp_startup_timeout"
			if scenario != "timeout" {
				cancel()
				want = "wisp_startup_canceled"
			}
			select {
			case err := <-done:
				if err == nil || err.Error() != want {
					t.Fatalf("result: %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("startup wait ignored deadline/cancellation")
			}
		})
	}
}

// A real gRPC transport with injected upstream failures; deliberately not a
// claim of full Core/Wisp integration (see TestManagedLive's private fixture).
type startupWisp struct {
	pb.UnimplementedServiceServer
	upstream atomic.Bool
	blocked  atomic.Bool
	control  atomic.Bool
}

func (s *startupWisp) GetPublicSetting(ctx context.Context, _ *pb.Empty) (*pb.PublicSettingResponse, error) {
	if s.blocked.Load() {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return &pb.PublicSettingResponse{Status: &pb.Status{Ok: s.upstream.Load(), Err: "private-token-url"}, Data: &pb.PublicSetting{GptApiKey: "private-api-key"}}, nil
}

func (s *startupWisp) DispatchTask(stream pb.Service_DispatchTaskServer) error {
	s.control.Store(true)
	defer s.control.Store(false)
	<-stream.Context().Done()
	return stream.Context().Err()
}

func TestManagedChild(t *testing.T) {
	if os.Getenv("CHEN_STARTUP_TEST_CHILD") != "1" {
		return
	}
	os.Args = []string{"chen-go"}
	if run() != nil {
		os.Exit(1)
	}
	os.Exit(0)
}

func eventually(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(12 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("condition did not become true")
}

func unusedAddress(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := l.Addr().String()
	l.Close()
	return address
}

func TestManagedStartupAndReadiness(t *testing.T) {
	for _, scenario := range []string{"delayed_wisp", "upstream_recovery", "timeout", "cancel"} {
		t.Run(scenario, func(t *testing.T) {
			fake := &startupWisp{}
			fake.upstream.Store(scenario != "upstream_recovery")
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			server := grpc.NewServer()
			pb.RegisterServiceServer(server, fake)
			defer server.Stop()
			if scenario == "upstream_recovery" {
				go server.Serve(listener)
			}
			address := unusedAddress(t)
			logPath := filepath.Join(t.TempDir(), "child.log")
			log, err := os.Create(logPath)
			if err != nil {
				t.Fatal(err)
			}
			defer log.Close()
			cmd := exec.Command(os.Args[0], "-test.run=^TestManagedChild$")
			timeout := "10s"
			if scenario == "timeout" {
				timeout = "300ms"
			}
			cmd.Env = append(os.Environ(), "CHEN_STARTUP_TEST_CHILD=1", "CHEN_STARTUP_TIMEOUT="+timeout,
				"CHEN_WISP_ADDRESS="+listener.Addr().String(), "CHEN_HTTP_ADDR="+address, "CHEN_STATE_DIR="+t.TempDir())
			cmd.Stdout, cmd.Stderr = log, log
			if err := cmd.Start(); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- cmd.Wait() }()
			t.Cleanup(func() { _ = cmd.Process.Kill() })
			eventually(t, func() bool {
				b, _ := os.ReadFile(logPath)
				return bytes.Contains(b, []byte("waiting_for_wisp_initialization"))
			})
			client := &http.Client{Timeout: 3 * time.Second}
			status := func(path string) int {
				r, err := client.Get("http://" + address + path)
				if err != nil {
					return 0
				}
				defer r.Body.Close()
				return r.StatusCode
			}
			if status("/healthy") != 0 || status("/ready") != 0 {
				t.Fatal("HTTP opened before Wisp initialization")
			}
			if scenario == "cancel" {
				_ = cmd.Process.Signal(syscall.SIGTERM)
			}
			if scenario == "timeout" || scenario == "cancel" {
				select {
				case err := <-done:
					if err == nil {
						t.Fatal("startup failure was hidden")
					}
				case <-time.After(4 * time.Second):
					t.Fatal("startup did not stop")
				}
				return
			}
			if scenario == "delayed_wisp" {
				go server.Serve(listener)
			}
			fake.upstream.Store(true)
			eventually(t, func() bool { return status("/ready") == 200 && fake.control.Load() })
			fake.upstream.Store(false)
			if status("/ready") != 503 || status("/healthy") != 200 {
				t.Fatal("upstream failure changed liveness or remained ready")
			}
			fake.upstream.Store(true)
			eventually(t, func() bool { return status("/ready") == 200 })
			fake.blocked.Store(true)
			if status("/ready") != 503 {
				t.Fatal("hung RPC did not time out")
			}
			fake.blocked.Store(false)
			server.Stop()
			eventually(t, func() bool { return status("/ready") == 503 })
			if status("/healthy") != 200 {
				t.Fatal("lost Wisp stopped Chen liveness")
			}
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("shutdown blocked")
			}
			b, _ := os.ReadFile(logPath)
			if bytes.Contains(b, []byte("private-")) {
				t.Fatal("probe leaked private RPC data")
			}
		})
	}
}
