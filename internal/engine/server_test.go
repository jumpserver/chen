package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type lifecycleProvider struct {
	jumpserver.Provider
	openCalls    atomic.Int32
	openStarted  chan struct{}
	openRelease  chan struct{}
	openError    error
	closeStarted chan struct{}
	closeRelease chan struct{}
	closeOnce    sync.Once
}

func (p *lifecycleProvider) Open(ctx context.Context, _, _ string) (*jumpserver.Grant, error) {
	p.openCalls.Add(1)
	if p.openStarted != nil {
		select {
		case p.openStarted <- struct{}{}:
		default:
		}
	}
	if p.openRelease != nil {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-p.openRelease:
		}
	}
	if p.openError != nil {
		return nil, p.openError
	}
	return nil, errors.New("connection_token_rejected")
}
func (p *lifecycleProvider) Close(context.Context, *jumpserver.Grant) error {
	if p.closeStarted != nil {
		p.closeOnce.Do(func() { close(p.closeStarted) })
	}
	if p.closeRelease != nil {
		<-p.closeRelease
	}
	return nil
}

func engineRequest(method, path, body, token string) *http.Request {
	if method == "POST" && strings.HasSuffix(path, "/sessions") && body != "" {
		var payload map[string]any
		if json.Unmarshal([]byte(body), &payload) == nil {
			if _, exists := payload["request_id"]; !exists {
				payload["request_id"] = "test-only-stable-create-nonce"
				encoded, _ := json.Marshal(payload)
				body = string(encoded)
			}
		}
	}
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if body != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		r.Header.Set("token", token)
	}
	return r
}

func seedEngineSession(v *Server, token string) *session {
	ctx, cancel := context.WithCancel(v.ctx)
	s := &session{id: "ses_test", grant: &jumpserver.Grant{ID: "core_test", Config: jumpserver.ConnectionConfig{Dialect: "mysql"}, Permissions: jumpserver.Permissions{Connect: true}, ExpiresAt: time.Now().Add(time.Hour)}, ctx: ctx, cancel: cancel, consoles: map[string]*console{}, executions: map[string]*execution{}, results: map[string]*Result{}, subscribers: map[uint64]chan Event{}}
	v.sessions[tokenKey(token)] = s
	return s
}

func TestPendingCreationCountsCapacityAndSameTokenSharesAttempt(t *testing.T) {
	p := &lifecycleProvider{openStarted: make(chan struct{}, 1), openRelease: make(chan struct{}), openError: errors.New("face_monitor_requires_legacy_chen")}
	v := New(p, Options{MaxSessions: 1})
	t.Cleanup(v.Close)
	h := v.Handler()
	first, again := httptest.NewRecorder(), httptest.NewRecorder()
	firstDone, againDone := make(chan struct{}), make(chan struct{})
	go func() {
		h.ServeHTTP(first, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"same"}`, ""))
		close(firstDone)
	}()
	<-p.openStarted
	other := httptest.NewRecorder()
	h.ServeHTTP(other, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"other"}`, ""))
	if other.Code != 429 {
		t.Fatalf("pending attempt bypassed session limit: %d", other.Code)
	}
	go func() {
		h.ServeHTTP(again, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"same"}`, ""))
		close(againDone)
	}()
	close(p.openRelease)
	<-firstDone
	<-againDone
	if p.openCalls.Load() != 1 || first.Code != 403 || again.Code != 403 || !strings.Contains(first.Body.String(), "face_monitor_requires_legacy_chen") {
		t.Fatalf("idempotent auth/policy diagnostic failed: calls=%d first=%d retry=%d body=%s", p.openCalls.Load(), first.Code, again.Code, first.Body.String())
	}
}

func TestCloseRemovesActiveTokenAndCreationReferences(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{MaxSessions: 1})
	t.Cleanup(v.Close)
	s := seedEngineSession(v, "session-token")
	done := make(chan struct{})
	close(done)
	c := &creation{done: done, token: "session-token", session: s}
	v.creations[tokenKey("connect-token")] = c
	v.closeSession(s, "user_closed")
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("GET", "/chen/api/v1/sessions/ses_test", "", "session-token"))
	if w.Code != 401 {
		t.Fatalf("closed token stayed usable: %d", w.Code)
	}
	v.mu.RLock()
	remaining := len(v.sessions)
	held := c.session != nil || c.token != ""
	expiry := c.expiresAt
	v.mu.RUnlock()
	if remaining != 0 || held || expiry.IsZero() {
		t.Fatal("closed session retained active capacity/results/token")
	}
	w = httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"next"}`, ""))
	if w.Code == 429 || p.openCalls.Load() != 1 {
		t.Fatal("closed session did not release session capacity")
	}
}

func TestLocalClosedStateDeniesAccessBeforeProviderCleanup(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	s := seedEngineSession(v, "session-token")
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("GET", "/chen/api/v1/executions/any", "", "session-token"))
	if w.Code != 410 {
		t.Fatalf("local closed state did not deny stale index access: %d", w.Code)
	}
	// This synthetic state did not pass through closeSession, so unregister it.
	v.mu.Lock()
	delete(v.sessions, tokenKey("session-token"))
	v.mu.Unlock()
	s.cancel()
}

func TestCloseContextWaitsForPreviouslyClosedSessionCleanup(t *testing.T) {
	p := &lifecycleProvider{closeStarted: make(chan struct{}), closeRelease: make(chan struct{})}
	v := New(p, Options{})
	s := seedEngineSession(v, "token")
	v.closeSession(s, "user_closed")
	<-p.closeStarted
	done := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		done <- v.CloseContext(ctx)
	}()
	select {
	case err := <-done:
		t.Fatalf("shutdown skipped pending cleanup after active-map removal: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	close(p.closeRelease)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestCreationFailuresExposeOnlyExplicitStaticDiagnostics(t *testing.T) {
	p := &lifecycleProvider{openError: errors.New("dial dbuser:synthetic-password@db.example.invalid failed")}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"test"}`, ""))
	if w.Code != 403 || strings.Contains(w.Body.String(), "synthetic-password") || !strings.Contains(w.Body.String(), "authentication_failed") {
		t.Fatalf("unsafe authentication error: %s", w.Body.String())
	}
}

func TestExpiredCreationTombstoneIsReclaimed(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	done := make(chan struct{})
	close(done)
	v.creations[tokenKey("test")] = &creation{done: done, err: errors.New("stale"), expiresAt: time.Now().Add(-time.Second)}
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"test"}`, ""))
	if p.openCalls.Load() != 1 {
		t.Fatal("expired creation permanently consumed idempotency capacity")
	}
}

func TestCreateNoncePreventsConsumedTokenReplay(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	h := v.Handler()
	request := func(nonce string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"same-token","request_id":"`+nonce+`"}`, ""))
		return w
	}
	if w := request(""); w.Code != 400 {
		t.Fatal("create request accepted without a browser nonce")
	}
	if w := request("short"); w.Code != 400 {
		t.Fatal("create request accepted a short nonce")
	}
	first := request("first-browser-random-nonce")
	if first.Code != 403 {
		t.Fatalf("initial authorization failure=%d", first.Code)
	}
	again := request("first-browser-random-nonce")
	if again.Code != 403 || p.openCalls.Load() != 1 {
		t.Fatal("same nonce did not recover the same authorization attempt")
	}
	other := request("second-browser-random-nonce")
	if other.Code != 409 || strings.Contains(other.Body.String(), `"token":`) {
		t.Fatalf("different browser claimed an already-used token: %s", other.Body.String())
	}
}

func TestSingleSelectorDoesNotShareBrowserAttempts(t *testing.T) {
	p := &lifecycleProvider{openError: errors.New("selector_not_found")}
	v := New(p, Options{SingleMode: true})
	t.Cleanup(v.Close)
	h := v.Handler()
	for _, nonce := range []string{"first-browser-random-nonce", "first-browser-random-nonce", "second-browser-random-nonce"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", `{"selector":"example","request_id":"`+nonce+`"}`, ""))
		if w.Code != 403 || !strings.Contains(w.Body.String(), "selector_not_found") {
			t.Fatalf("unexpected response: %s", w.Body.String())
		}
	}
	if p.openCalls.Load() != 2 {
		t.Fatalf("selector attempts were shared or replayed: %d", p.openCalls.Load())
	}
}

func TestSessionModesRejectOtherCredentialTypes(t *testing.T) {
	for _, single := range []bool{false, true} {
		p := &lifecycleProvider{}
		v := New(p, Options{SingleMode: single})
		t.Cleanup(v.Close)
		body := `{"selector":"example"}`
		if single {
			body = `{"connect_token":"core-token"}`
		}
		for _, request := range []string{body, `{"connect_token":"core-token","selector":"example"}`} {
			w := httptest.NewRecorder()
			v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/sessions", request, ""))
			if w.Code != 400 || p.openCalls.Load() != 0 {
				t.Fatalf("mode=%v reached provider with wrong credentials", single)
			}
		}
	}
}

func TestHTTPRejectsAmbiguousAuthOriginAndWrongJSONMediaType(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	seedEngineSession(v, "token")
	for _, origin := range []string{"http://example.com/path", "http://example.com?query", "http://example.com#fragment", "http://evil.invalid"} {
		r := engineRequest("GET", "http://example.com/chen/api/v1/sessions/ses_test", "", "token")
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		v.Handler().ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatalf("accepted non-origin value %q", origin)
		}
	}
	r := engineRequest("GET", "/chen/api/v1/sessions/ses_test", "", "token")
	r.Header.Set("Authorization", "Bearer different")
	w := httptest.NewRecorder()
	v.Handler().ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("accepted conflicting session credentials")
	}
	r = engineRequest("POST", "/chen/api/v1/sessions", `{"connect_token":"test"}`, "")
	r.Header.Set("Content-Type", "application/jsonp")
	w = httptest.NewRecorder()
	v.Handler().ServeHTTP(w, r)
	if w.Code != 415 {
		t.Fatal("accepted JSON prefix media type")
	}
}

type eventRecorder struct {
	header  http.Header
	body    bytes.Buffer
	flushed chan struct{}
	code    int
}

func (e *eventRecorder) Header() http.Header         { return e.header }
func (e *eventRecorder) Write(p []byte) (int, error) { return e.body.Write(p) }
func (e *eventRecorder) WriteHeader(status int)      { e.code = status }
func (e *eventRecorder) Flush() {
	select {
	case e.flushed <- struct{}{}:
	default:
	}
}

func captureEvents(t *testing.T, v *Server, lastID string, action func(*session), s *session) string {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := engineRequest("GET", "/chen/api/v1/sessions/ses_test/events", "", "token").WithContext(ctx)
	if lastID != "" {
		r.Header.Set("Last-Event-ID", lastID)
	}
	w := &eventRecorder{header: make(http.Header), flushed: make(chan struct{}, 16)}
	done := make(chan struct{})
	go func() { v.Handler().ServeHTTP(w, r); close(done) }()
	select {
	case <-w.flushed:
	case <-time.After(time.Second):
		t.Fatal("event stream did not establish promptly")
	}
	if action != nil {
		action(s)
	} else {
		cancel()
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		cancel()
		t.Fatal("event stream did not finish")
	}
	return w.body.String()
}

func TestSSEResetIncludesConsistentConsolesAndExecutionSnapshots(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	s := seedEngineSession(v, "token")
	ctx, cancel := context.WithCancel(s.ctx)
	c := &console{id: "con_lost", context: sqlservice.Context{Database: "app", Schema: "public"}, ctx: ctx, cancel: cancel}
	c.lost.Store(true)
	s.consoles[c.id] = c
	s.executions["exe_test"] = &execution{view: ExecutionView{ID: "exe_test", Status: "succeeded"}}
	body := captureEvents(t, v, "999", nil, s)
	for _, want := range []string{`"type":"reset"`, `"consoles":[`, `"id":"con_lost"`, `"state":"lost"`, `"executions":[`, `"id":"exe_test"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("reset omitted %s: %s", want, body)
		}
	}
}

func TestSSEReplaysCursorAndDeliversFinalClose(t *testing.T) {
	p := &lifecycleProvider{}
	v := New(p, Options{})
	t.Cleanup(v.Close)
	s := seedEngineSession(v, "token")
	s.emit("first", map[string]int{"value": 1})
	s.emit("second", map[string]int{"value": 2})
	body := captureEvents(t, v, "1", func(s *session) { v.closeSession(s, "user_closed") }, s)
	if strings.Contains(body, "event: first") || strings.Contains(body, "event: reset") || !strings.Contains(body, "event: second") || !strings.Contains(body, "event: session.closed") || !strings.Contains(body, `"reason":"user_closed"`) {
		t.Fatalf("incorrect replay/close delivery: %s", body)
	}
}
