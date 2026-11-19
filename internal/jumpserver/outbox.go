package jumpserver

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

// A file represents a recoverable unit. Cleanup operations share one file so a
// process crash cannot persist only half the local intention to close a session.
type envelope struct {
	Replay       *protobuf.ReplayRequest              `json:"replay,omitempty"`
	Version      int                                  `json:"version"`
	Command      *protobuf.CommandRequest             `json:"command,omitempty"`
	Lifecycle    *protobuf.SessionLifecycleLogRequest `json:"lifecycle,omitempty"`
	Finish       *protobuf.SessionFinishRequest       `json:"finish,omitempty"`
	Forward      *protobuf.ForwardDeleteRequest       `json:"forward,omitempty"`
	TicketCancel *protobuf.TicketRequest              `json:"ticket_cancel,omitempty"`
}

type outbox struct {
	dir string
	mu  sync.Mutex
}

func newOutbox(dir string) (*outbox, error) {
	if strings.TrimSpace(dir) == "" {
		return nil, errors.New("audit_spool_directory_required")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("invalid_audit_spool_directory")
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	box := &outbox{dir: dir}
	// Check write/fsync ability at startup, before accepting any query.
	probe, err := os.CreateTemp(dir, ".probe-")
	if err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	err = probe.Sync()
	err = errors.Join(err, probe.Close(), os.Remove(probe.Name()))
	if err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	return box, nil
}

func randomID() (string, error) {
	var data [16]byte
	if _, err := rand.Read(data[:]); err != nil {
		return "", errors.New("secure_random_unavailable")
	}
	data[6] = data[6]&0x0f | 0x40
	data[8] = data[8]&0x3f | 0x80
	encoded := hex.EncodeToString(data[:])
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}

func closeEnvelope(g *Grant, reason string, connected bool) envelope {
	result := envelope{Finish: &protobuf.SessionFinishRequest{Id: g.ID, DateEnd: time.Now().Unix(), Success: connected}}
	if g.gatewayID != "" {
		result.Forward = &protobuf.ForwardDeleteRequest{Id: g.gatewayID}
	}
	if connected {
		result.Lifecycle = &protobuf.SessionLifecycleLogRequest{SessionId: g.ID,
			Event: protobuf.SessionLifecycleLogRequest_AssetConnectFinished, Reason: reason}
	}
	return result
}

func (o *outbox) put(value envelope) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	id, err := randomID()
	if err != nil {
		return err
	}
	name := fmt.Sprintf("%020d-%s.json", time.Now().UnixNano(), id)
	return o.write(name, value)
}

// write performs file+directory fsync before acknowledging durable acceptance.
// SQL text is audit data; every temporary and final file is owner-readable only.
func (o *outbox) write(name string, value envelope) error {
	value.Version = 1
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(o.dir, ".pending-")
	if err != nil {
		return err
	}
	temp := file.Name()
	defer os.Remove(temp)
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if err := os.Rename(temp, filepath.Join(o.dir, name)); err != nil {
		return err
	}
	return syncDirectory(o.dir)
}

func syncDirectory(dir string) error {
	file, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}

func (p *WispProvider) flush(ctx context.Context) {
	p.flushMu.Lock()
	defer p.flushMu.Unlock()
	files, err := os.ReadDir(p.spool.dir) // sorted filenames preserve local order.
	if err != nil {
		p.report("audit_spool_read_failed")
		return
	}
	blockedCommands := make(map[string]bool)
	for _, file := range files {
		if ctx.Err() != nil {
			return
		}
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".json") {
			continue
		}
		path := filepath.Join(p.spool.dir, file.Name())
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() > 16<<20 {
			p.report("audit_spool_file_invalid")
			continue
		}
		data, err := os.ReadFile(path)
		if err != nil {
			p.report("audit_spool_read_failed")
			continue
		}
		var entry envelope
		if err := json.Unmarshal(data, &entry); err != nil || entry.Version != 1 {
			p.report("audit_spool_file_invalid")
			continue
		}
		commandSession := ""
		if entry.Command != nil {
			commandSession = entry.Command.Sid
			if blockedCommands[commandSession] {
				continue
			}
		}
		if err := p.deliver(ctx, &entry); err != nil {
			// Successful sub-operations are checkpointed. Unknown remote outcomes
			// remain at-least-once; Wisp UploadCommand has no idempotency key.
			p.spool.mu.Lock()
			writeErr := p.spool.write(file.Name(), entry)
			p.spool.mu.Unlock()
			if writeErr != nil {
				p.report("audit_spool_checkpoint_failed")
			}
			p.report("audit_delivery_deferred")
			// Preserve this session's command order while allowing unrelated
			// sessions and resource cleanup to make progress during an outage.
			if commandSession != "" {
				blockedCommands[commandSession] = true
			}
			continue
		}
		if err := os.Remove(path); err != nil {
			p.report("audit_spool_remove_failed")
			return
		}
		if err := syncDirectory(p.spool.dir); err != nil {
			p.report("audit_spool_sync_failed")
			return
		}
	}
}

func (p *WispProvider) deliver(ctx context.Context, e *envelope) error {
	var result error
	if e.Replay != nil {
		if _, err := p.replays.finish(e.Replay.SessionId); err != nil {
			return errors.New("session_replay_prepare_failed")
		}
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.UploadReplayFile(requestCtx, e.Replay)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			result = errors.New("session_replay_upload_deferred")
		} else {
			_ = os.Remove(e.Replay.ReplayFilePath)
			_ = os.Remove(strings.TrimSuffix(e.Replay.ReplayFilePath, ".gz"))
			_ = syncDirectory(p.replays.dir)
			e.Replay = nil
		}
	}
	if e.TicketCancel != nil {
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.CancelTicket(requestCtx, e.TicketCancel)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			result = errors.New("command_review_cancel_deferred")
		} else {
			e.TicketCancel = nil
		}
	}
	if e.Command != nil {
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.UploadCommand(requestCtx, e.Command)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			return errors.New("command_audit_delivery_failed")
		}
		e.Command = nil
	}
	if e.Forward != nil {
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.DeleteForward(requestCtx, e.Forward)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			result = errors.New("forward_cleanup_deferred")
		} else {
			e.Forward = nil
		}
	}
	if e.Lifecycle != nil {
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.RecordSessionLifecycleLog(requestCtx, e.Lifecycle)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			result = errors.Join(result, errors.New("session_lifecycle_delivery_failed"))
		} else {
			e.Lifecycle = nil
		}
	}
	// Keep the session pending until its replay has an acknowledged upload.
	// Independent gateway and lifecycle cleanup above may still make progress.
	if e.Finish != nil && e.Replay == nil {
		requestCtx, cancel := p.rpcContext(ctx)
		response, err := p.client.FinishSession(requestCtx, e.Finish)
		cancel()
		if err != nil || response == nil || !statusOK(response.Status) {
			result = errors.Join(result, errors.New("session_finish_deferred"))
		} else {
			e.Finish = nil
		}
	}
	return result
}
