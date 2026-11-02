package main

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	pb "github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func waitWisp(ctx context.Context, client pb.ServiceClient, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	slog.Info("waiting_for_wisp_initialization", "timeout", timeout)
	for attempt := 1; ; attempt++ {
		if ctx.Err() == nil && jumpserver.ProbeWisp(ctx, client) == nil {
			slog.Info("wisp_initialized")
			return nil
		}
		if ctx.Err() != nil {
			if errors.Is(ctx.Err(), context.Canceled) {
				return errors.New("wisp_startup_canceled")
			}
			return errors.New("wisp_startup_timeout")
		}
		slog.Warn("wisp_initialization_pending", "attempt", attempt)
		timer := time.NewTimer(time.Second)
		select {
		case <-ctx.Done():
			timer.Stop()
		case <-timer.C:
		}
	}
}
