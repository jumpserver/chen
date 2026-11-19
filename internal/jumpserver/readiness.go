package jumpserver

import (
	"context"
	"errors"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

// ProbeWisp performs a read-only RPC, including its application status. It
// neither consumes a connection token nor changes sessions/audits. Wisp opens
// gRPC after registration/key validation and terminal configuration have loaded.
// This proves a Core public-settings read, not Core's WebSocket/DB/Redis health.
func ProbeWisp(ctx context.Context, client protobuf.ServiceClient) error {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	response, err := client.GetPublicSetting(ctx, &protobuf.Empty{})
	if err != nil || response == nil || !statusOK(response.Status) || response.Data == nil {
		// RPC errors and response data may contain URLs, credentials and API keys.
		return errors.New("wisp_not_ready")
	}
	return nil
}

func (p *WispProvider) Ready(ctx context.Context) error {
	p.mu.RLock()
	available := p.controlAvailable
	p.mu.RUnlock()
	if !available {
		return ErrControlNotReady
	}
	if err := ProbeWisp(ctx, p.client); err != nil {
		return err
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	if !p.controlAvailable {
		return ErrControlNotReady
	}
	return nil
}
