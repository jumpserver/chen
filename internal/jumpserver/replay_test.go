package jumpserver

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
)

func (w *testWisp) UploadReplayFile(_ context.Context, req *protobuf.ReplayRequest, _ ...grpc.CallOption) (*protobuf.ReplayResponse, error) {
	if _, err := os.Stat(req.ReplayFilePath); err != nil {
		return nil, err
	}
	return &protobuf.ReplayResponse{Status: &protobuf.Status{Ok: true}}, nil
}
func (w *wireWisp) UploadReplayFile(ctx context.Context, req *protobuf.ReplayRequest) (*protobuf.ReplayResponse, error) {
	return w.backend.UploadReplayFile(ctx, req)
}

type faceWisp struct {
	*testWisp
	fail   bool
	joined string
}

func (w *faceWisp) JoinFaceMonitor(_ context.Context, req *protobuf.JoinFaceMonitorRequest, _ ...grpc.CallOption) (*protobuf.JoinFaceMonitorResponse, error) {
	w.joined = req.SessionId
	return &protobuf.JoinFaceMonitorResponse{Status: &protobuf.Status{Ok: !w.fail}}, nil
}
func TestFaceMonitorJoinRequiredBeforeOpen(t *testing.T) {
	for _, fail := range []bool{false, true} {
		w := &faceWisp{testWisp: &testWisp{token: validToken()}, fail: fail}
		w.token.FaceMonitorToken = "synthetic-face-token"
		p, err := NewWispProvider(w, Options{SpoolDir: t.TempDir()})
		if err != nil {
			t.Fatal(err)
		}
		g, err := p.Open(context.Background(), "synthetic", "127.0.0.1")
		if fail {
			if err == nil || err.Error() != "face_monitor_join_failed" || g != nil {
				t.Fatalf("join failure accepted: %v", err)
			}
			p.flush(context.Background())
			if len(w.finishes) != 1 {
				t.Fatal("failed face join left Core session open")
			}
		} else {
			if err != nil || w.joined != g.ID {
				t.Fatalf("join missing: %v", err)
			}
			if err := p.Close(context.Background(), g); err != nil {
				t.Fatal(err)
			}
		}
	}
}
func TestReplayDurabilityRedactionAndRecovery(t *testing.T) {
	dir := t.TempDir()
	w := &testWisp{token: validToken()}
	p, err := NewWispProvider(w, Options{SpoolDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	g, err := p.Open(context.Background(), "synthetic", "127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Record(context.Background(), g, AuditRecord{SQL: "select 1", Output: "rows=1 " + g.Config.Password, StartedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	// Simulate restart without Close; the durable cast must become an upload.
	recovered, err := NewWispProvider(w, Options{SpoolDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(recovered.replays.dir, g.ID+".cast.gz")
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	defer gz.Close()
	data, err := io.ReadAll(gz)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), g.Config.Password) || !strings.Contains(string(data), "[redacted]") {
		t.Fatal("replay redaction failed")
	}
	var header map[string]any
	if err := json.Unmarshal([]byte(strings.Split(string(data), "\n")[0]), &header); err != nil || header["version"] != float64(2) {
		t.Fatal("invalid asciinema header")
	}
	recovered.flush(context.Background())
	if len(w.finishes) != 1 || len(w.commands) != 1 {
		t.Fatalf("recovery did not deliver close/audit: %d/%d", len(w.finishes), len(w.commands))
	}
	if _, err := os.Stat(filepath.Join(recovered.replays.dir, g.ID+".cast")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("uncompressed cast retained")
	}
}

// Wisp can upload and remove the compressed file, then lose its RPC reply.
// A retry must regenerate from the canonical cast rather than finish without it.
type lostReplayReplyWisp struct {
	*testWisp
	attempts int
	uploaded []byte
}

func (w *lostReplayReplyWisp) UploadReplayFile(_ context.Context, req *protobuf.ReplayRequest, _ ...grpc.CallOption) (*protobuf.ReplayResponse, error) {
	payload, err := os.ReadFile(req.ReplayFilePath)
	if err != nil {
		return nil, err
	}
	if err := os.Remove(req.ReplayFilePath); err != nil {
		return nil, err
	}
	w.attempts++
	if w.attempts == 1 {
		w.uploaded = payload
		return nil, errors.New("lost reply after upload")
	}
	if string(w.uploaded) != string(payload) {
		return nil, errors.New("retry changed replay")
	}
	return &protobuf.ReplayResponse{Status: &protobuf.Status{Ok: true}}, nil
}
func TestReplayLostUploadReplyRetainsCanonicalCast(t *testing.T) {
	dir := t.TempDir()
	w := &lostReplayReplyWisp{testWisp: &testWisp{token: validToken()}}
	p, err := NewWispProvider(w, Options{SpoolDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	g, err := p.Open(context.Background(), "synthetic", "127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Record(context.Background(), g, AuditRecord{SQL: "select 42", Output: "rows=1", StartedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(context.Background(), g); err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	if w.attempts != 1 || len(w.finishes) != 0 {
		t.Fatalf("unknown replay outcome finished session: %d/%d", w.attempts, len(w.finishes))
	}
	if _, err := os.Stat(filepath.Join(p.replays.dir, g.ID+".cast")); err != nil {
		t.Fatal("canonical cast lost", err)
	}
	// Restart must resume the existing close envelope once, not create another.
	p, err = NewWispProvider(w, Options{SpoolDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	p.flush(context.Background())
	if w.attempts != 2 || len(w.finishes) != 1 {
		t.Fatalf("retry failed: %d/%d", w.attempts, len(w.finishes))
	}
	files, err := os.ReadDir(p.replays.dir)
	if err != nil || len(files) != 0 {
		t.Fatalf("acknowledged replay retained: %v/%v", files, err)
	}
}
