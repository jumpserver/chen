package jumpserver

import (
	"context"
	"errors"
	"testing"
	"time"

	pb "github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
)

type readinessClient struct {
	pb.ServiceClient
	response     *pb.PublicSettingResponse
	err          error
	calls        int
	beforeReturn func()
}

type blockingProbeClient struct {
	pb.ServiceClient
	entered chan struct{}
}

func (c blockingProbeClient) GetPublicSetting(ctx context.Context, _ *pb.Empty, _ ...grpc.CallOption) (*pb.PublicSettingResponse, error) {
	close(c.entered)
	<-ctx.Done()
	return nil, ctx.Err()
}

func TestProbeWispDeadlineAndCancellation(t *testing.T) {
	for _, scenario := range []string{"rpc_deadline", "parent_deadline", "cancel"} {
		t.Run(scenario, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if scenario == "parent_deadline" {
				var stop context.CancelFunc
				ctx, stop = context.WithTimeout(ctx, 50*time.Millisecond)
				defer stop()
			}
			client := blockingProbeClient{entered: make(chan struct{})}
			done := make(chan error, 1)
			start := time.Now()
			go func() { done <- ProbeWisp(ctx, client) }()
			<-client.entered
			if scenario == "cancel" {
				cancel()
			}
			select {
			case err := <-done:
				if err == nil || err.Error() != "wisp_not_ready" {
					t.Fatalf("unsanitized result: %v", err)
				}
				if scenario == "rpc_deadline" && time.Since(start) < 1900*time.Millisecond {
					t.Fatal("RPC deadline ended early")
				}
			case <-time.After(3 * time.Second):
				t.Fatal("probe exceeded its deadline")
			}
		})
	}
}

func (c *readinessClient) GetPublicSetting(ctx context.Context, _ *pb.Empty, _ ...grpc.CallOption) (*pb.PublicSettingResponse, error) {
	c.calls++
	if _, ok := ctx.Deadline(); !ok {
		panic("unbounded readiness RPC")
	}
	if c.beforeReturn != nil {
		c.beforeReturn()
	}
	return c.response, c.err
}

func TestProbeWispRejectsIncompleteOrFailedResponses(t *testing.T) {
	for _, tc := range []struct {
		response *pb.PublicSettingResponse
		err      error
		ok       bool
	}{
		{nil, nil, false},
		{&pb.PublicSettingResponse{}, nil, false},
		{&pb.PublicSettingResponse{Status: &pb.Status{Ok: true}}, nil, false},
		{&pb.PublicSettingResponse{Status: &pb.Status{Err: "secret"}, Data: &pb.PublicSetting{}}, nil, false},
		{nil, errors.New("secret"), false},
		{&pb.PublicSettingResponse{Status: &pb.Status{Ok: true}, Data: &pb.PublicSetting{}}, nil, true},
	} {
		client := &readinessClient{response: tc.response, err: tc.err}
		err := ProbeWisp(context.Background(), client)
		if (err == nil) != tc.ok || (err != nil && err.Error() != "wisp_not_ready") {
			t.Fatalf("unexpected result: %v", err)
		}
	}
}

func TestReadinessRequiresLocalControlWorker(t *testing.T) {
	client := &readinessClient{response: &pb.PublicSettingResponse{Status: &pb.Status{Ok: true}, Data: &pb.PublicSetting{}}}
	p, err := NewWispProvider(client, Options{SpoolDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if p.Ready(context.Background()) == nil || client.calls != 0 {
		t.Fatal("unstarted control worker reported ready")
	}
	p.setControlAvailable(true)
	if err := p.Ready(context.Background()); err != nil {
		t.Fatal(err)
	}
	p.setControlAvailable(false)
	if p.Ready(context.Background()) == nil {
		t.Fatal("lost control stream reported ready")
	}
	p.setControlAvailable(true)
	client.beforeReturn = func() { p.setControlAvailable(false) }
	if !errors.Is(p.Ready(context.Background()), ErrControlNotReady) {
		t.Fatal("control loss during a successful RPC reported ready")
	}
}
