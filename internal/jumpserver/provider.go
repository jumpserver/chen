package jumpserver

import (
	"context"
	"errors"
	"net"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/protobuf/proto"
)

type Options struct {
	SpoolDir           string
	ReplayDir          string
	RequestTimeout     time.Duration
	RetryInterval      time.Duration
	CheckInterval      time.Duration
	ReviewTimeout      time.Duration
	ReviewPollInterval time.Duration
	// OnError receives operation codes only, never RPC messages or credentials.
	OnError func(error)
}

type WispProvider struct {
	client           protobuf.ServiceClient
	options          Options
	mu               sync.RWMutex
	grants           map[string]*Grant
	spool            *outbox
	replays          *replayStore
	wake             chan struct{}
	runOnce          sync.Once
	flushMu          sync.Mutex
	controlMonitored bool
	controlAvailable bool
}

func NewWispProvider(client protobuf.ServiceClient, options Options) (*WispProvider, error) {
	if client == nil {
		return nil, errors.New("wisp_client_required")
	}
	if options.RequestTimeout <= 0 {
		options.RequestTimeout = 10 * time.Second
	}
	if options.RetryInterval <= 0 {
		options.RetryInterval = 5 * time.Second
	}
	if options.CheckInterval <= 0 {
		options.CheckInterval = time.Second
	}
	if options.ReviewTimeout <= 0 || options.ReviewTimeout > 30*time.Minute {
		options.ReviewTimeout = 30 * time.Minute
	}
	if options.ReviewPollInterval <= 0 {
		options.ReviewPollInterval = 5 * time.Second
	}
	spool, err := newOutbox(options.SpoolDir)
	if err != nil {
		return nil, err
	}
	if options.ReplayDir == "" {
		options.ReplayDir = filepath.Join(options.SpoolDir, "replays")
	}
	replays, err := newReplayStore(options.ReplayDir)
	if err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	p := &WispProvider{client: client, options: options, grants: make(map[string]*Grant), spool: spool, replays: replays, wake: make(chan struct{}, 1)}
	if err := p.recoverReplays(); err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	return p, nil
}

func (p *WispProvider) rpcContext(ctx context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(ctx, p.options.RequestTimeout)
}

func statusOK(status *protobuf.Status) bool { return status != nil && status.Ok }

func (p *WispProvider) Open(ctx context.Context, token, remoteAddr string) (_ *Grant, resultErr error) {
	p.mu.RLock()
	controlUnavailable := p.controlMonitored && !p.controlAvailable
	p.mu.RUnlock()
	if controlUnavailable {
		// Do not consume a one-time Core connection token while its session
		// could not receive administrator controls.
		return nil, ErrControlNotReady
	}
	if strings.TrimSpace(token) == "" {
		return nil, errors.New("connection_token_required")
	}
	requestCtx, cancel := p.rpcContext(ctx)
	response, err := p.client.GetTokenAuthInfo(requestCtx, &protobuf.TokenRequest{Token: token})
	cancel()
	if err != nil {
		return nil, errors.New("token_exchange_failed")
	}
	if response == nil || !statusOK(response.Status) || response.Data == nil {
		return nil, errors.New("connection_token_rejected")
	}
	data := response.Data
	if err := validateToken(data); err != nil {
		return nil, err
	}
	matchers, err := compileMatchers(data.FilterRules)
	if err != nil {
		return nil, err
	}
	maskers, err := compileMaskers(data.DataMaskingRules)
	if err != nil {
		return nil, err
	}
	protocol := data.Asset.Protocols[0]
	now := time.Now()
	config := ConnectionConfig{
		Dialect: strings.ToLower(protocol.Name), Host: data.Asset.Address,
		Port: int(protocol.Port), User: data.Account.Username,
		Password: data.Account.Secret, Database: data.Asset.GetSpecific().GetDbName(),
		TLS: TLSConfig{ServerName: data.Asset.Address},
	}
	config.Dialect, _ = authorizedDialect(data)
	if specific := data.Asset.Specific; specific != nil {
		config.TLS = TLSConfig{
			Enabled: specific.UseSsl, ServerName: data.Asset.Address,
			InsecureSkipVerify: specific.AllowInvalidCert, Mode: specific.PgSslMode,
			CACert: specific.CaCert, ClientCert: specific.ClientCert, ClientKey: specific.ClientKey,
		}
	}
	if err := applyPlatformConnectionOptions(&config, data); err != nil {
		return nil, err
	}
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		remoteAddr = host
	}
	session := &protobuf.Session{
		UserId: data.User.Id, User: displayName(data.User.Name, data.User.Username),
		AssetId: data.Asset.Id, Asset: data.Asset.Name, OrgId: data.Asset.OrgId,
		AccountId: data.Account.Id, Account: displayName(data.Account.Name, data.Account.Username),
		Protocol: protocol.Name, DateStart: now.Unix(), TokenId: data.KeyId,
		LoginFrom: protobuf.Session_WT, RemoteAddr: remoteAddr,
	}
	// Set an ID before CreateSession. If the RPC succeeds remotely but its reply
	// is lost, compensating FinishSession still has the correct resource ID.
	session.Id, err = randomID()
	if err != nil {
		return nil, err
	}
	g := &Grant{
		ID: session.Id, OwnerID: data.User.Id, OwnerName: session.User,
		AssetID: data.Asset.Id, AssetName: data.Asset.Name, AccountName: session.Account,
		Config: config, ExpiresAt: time.Unix(data.ExpireInfo.ExpireAt, 0),
		Permissions: Permissions{Connect: data.Permission.EnableConnect,
			Upload: data.Permission.EnableUpload, Download: data.Permission.EnableDownload,
			Copy: data.Permission.EnableCopy, Paste: data.Permission.EnablePaste},
		MaxIdle:     time.Duration(data.Setting.MaxIdleTime) * time.Minute,
		MaxDuration: time.Duration(data.Setting.MaxSessionTime) * time.Hour,
		session:     session, matchers: matchers, maskers: maskers,
		created: now, lastActive: now, watchers: make(map[uint64]chan Control),
	}
	created := false
	defer func() {
		if resultErr == nil {
			return
		}
		if created {
			// A request context can already be canceled. Cleanup must still run.
			cleanupCtx, cleanupCancel := p.rpcContext(context.Background())
			defer cleanupCancel()
			if cleanupErr := p.Close(cleanupCtx, g); cleanupErr != nil {
				resultErr = errors.Join(resultErr, cleanupErr)
			}
		}
	}()
	requestCtx, cancel = p.rpcContext(ctx)
	created = true // RPC failure is an unknown outcome: always compensate.
	createdResponse, err := p.client.CreateSession(requestCtx, &protobuf.SessionCreateRequest{Data: session})
	cancel()
	if err != nil {
		return nil, errors.New("core_session_create_failed")
	}
	if createdResponse == nil || !statusOK(createdResponse.Status) || createdResponse.Data == nil || createdResponse.Data.Id == "" {
		if createdResponse != nil && createdResponse.Status != nil && !createdResponse.Status.Ok {
			created = false // explicit rejection created no Core resource.
		}
		return nil, errors.New("core_session_create_rejected")
	}
	// Core is authoritative for the returned session identifier. The other audit
	// identity fields remain those of the authenticated token.
	g.ID = createdResponse.Data.Id
	g.session = proto.Clone(session).(*protobuf.Session)
	g.session.Id = g.ID
	if strings.TrimSpace(data.FaceMonitorToken) != "" {
		requestCtx, cancel = p.rpcContext(ctx)
		joined, joinErr := p.client.JoinFaceMonitor(requestCtx, &protobuf.JoinFaceMonitorRequest{FaceMonitorToken: data.FaceMonitorToken, SessionId: g.ID})
		cancel()
		if joinErr != nil || joined == nil || !statusOK(joined.Status) {
			return nil, errors.New("face_monitor_join_failed")
		}
	}
	if err := p.replays.start(g); err != nil {
		return nil, errors.Join(ErrAuditDisk, err)
	}
	p.mu.Lock()
	if _, exists := p.grants[g.ID]; exists {
		p.mu.Unlock()
		return nil, errors.New("duplicate_core_session_id")
	}
	p.grants[g.ID] = g
	g.controlUnavailable = p.controlMonitored && !p.controlAvailable
	p.mu.Unlock()
	if len(data.Gateways) > 0 {
		forward, forwardErr := p.createForward(ctx, &protobuf.ForwardRequest{
			Host: config.Host, Port: protocol.Port, Gateways: data.Gateways,
		})
		g.mu.Lock()
		if g.closed {
			g.mu.Unlock()
			// Admin termination can race the forward RPC before Open returns.
			// Close may already have persisted a cleanup without this new ID.
			if forward != nil && forward.Id != "" {
				if cleanupErr := p.spool.put(envelope{Forward: &protobuf.ForwardDeleteRequest{Id: forward.Id}}); cleanupErr != nil {
					return nil, errors.Join(ErrClosed, ErrAuditDisk, cleanupErr)
				}
				p.signal()
			}
			return nil, ErrClosed
		}
		// A returned ID must be cleaned up even if another response field failed.
		if forward != nil && forward.Id != "" {
			g.gatewayID = forward.Id
		}
		if forwardErr != nil || forward == nil || !statusOK(forward.Status) || forward.Id == "" || forward.Port < 1 || forward.Port > 65535 {
			g.mu.Unlock()
			return nil, errors.New("gateway_forward_failed")
		}
		// Wisp forwards bind locally; TLS verification keeps the original asset
		// hostname rather than authenticating the local tunnel address.
		g.Config.Host, g.Config.Port = "127.0.0.1", int(forward.Port)
		g.mu.Unlock()
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := g.Check(); err != nil {
		return nil, err
	}
	return g, nil
}

func validateToken(data *protobuf.TokenAuthInfo) error {
	if data.Asset == nil || data.Account == nil || data.User == nil || data.Permission == nil || data.ExpireInfo == nil || data.Setting == nil {
		return errors.New("incomplete_connection_authorization")
	}
	if !data.Permission.EnableConnect {
		return errors.New("connect_permission_denied")
	}
	if data.ExpireInfo.ExpireAt <= time.Now().Unix() {
		return ErrExpired
	}

	if data.KeyId == "" || data.Asset.Id == "" || data.User.Id == "" || data.Account.Id == "" || data.Asset.Address == "" || data.Account.Username == "" {
		return errors.New("incomplete_connection_identity")
	}
	if secretType := data.Account.GetSecretType().GetValue(); secretType != "" && secretType != "password" {
		return errors.New("unsupported_database_credential_type")
	}
	if len(data.Asset.Protocols) != 1 || data.Asset.Protocols[0] == nil {
		return errors.New("ambiguous_connection_protocol")
	}
	protocol := data.Asset.Protocols[0]
	if _, err := authorizedDialect(data); err != nil {
		return err
	}
	if protocol.Port < 1 || protocol.Port > 65535 {
		return errors.New("invalid_authorized_database_port")
	}
	if data.Setting.MaxIdleTime < 0 || data.Setting.MaxSessionTime < 0 {
		return errors.New("invalid_session_time_limit")
	}
	return nil
}

func displayName(name, username string) string {
	return name + "(" + username + ")"
}

func (p *WispProvider) Connected(ctx context.Context, g *Grant) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := g.Check(); err != nil {
		return err
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if err := g.checkLocked(time.Now()); err != nil {
		return err
	}
	if g.connected {
		return nil
	}
	if err := p.enqueueLifecycle(g.ID, protobuf.SessionLifecycleLogRequest_AssetConnectSuccess, ""); err != nil {
		return err
	}
	g.connected = true
	return nil
}

func (p *WispProvider) Close(_ context.Context, g *Grant) error {
	if g == nil {
		return nil
	}
	g.closeMu.Lock()
	defer g.closeMu.Unlock()
	g.mu.Lock()
	g.closed = true
	if g.closeReason == "" {
		g.closeReason = "connect_disconnect"
	}
	cleanup := closeEnvelope(g, g.closeReason, g.connected)
	g.mu.Unlock()
	if g.closePersisted {
		return nil
	}
	replay, err := p.replays.finish(g.ID)
	if err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	cleanup.Replay = replay
	// All cleanup requests are persisted together before attempting any remote
	// mutation, allowing restart recovery and avoiding a half-written close.
	if err := p.spool.put(cleanup); err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	g.closePersisted = true
	p.mu.Lock()
	if p.grants[g.ID] == g {
		delete(p.grants, g.ID)
	}
	p.mu.Unlock()
	p.signal()
	return nil
}

func (p *WispProvider) Record(_ context.Context, g *Grant, record AuditRecord) error {
	if g == nil || g.session == nil {
		return errors.New("audit_session_required")
	}
	if record.StartedAt.IsZero() {
		record.StartedAt = time.Now()
	}
	risk := protobuf.RiskLevel(record.RiskLevel)
	if !record.RiskClassified && record.ACLID == "" {
		if matched := matchPolicy(g, record.SQL); matched != nil {
			record.ACLID, record.GroupID = matched.aclID, matched.groupID
			switch matched.action {
			case protobuf.CommandACL_Warning, protobuf.CommandACL_NotifyWarning:
				risk = protobuf.RiskLevel_Warning
			case protobuf.CommandACL_Reject:
				risk = protobuf.RiskLevel_Reject
			case protobuf.CommandACL_Review:
				risk = protobuf.RiskLevel_ReviewReject
			}
		}
	}
	output := record.Output
	if record.Error != "" {
		output = record.Error
	}
	// Result contents are never replayed here. Only a bounded status summary
	// enters command audit, avoiding a second unmasked copy of query results.
	for _, secret := range []string{g.Config.Password, g.Config.TLS.ClientKey} {
		if secret != "" {
			output = strings.ReplaceAll(output, secret, "[redacted]")
		}
	}
	output = truncate(output, 2048)
	request := &protobuf.CommandRequest{
		Sid: g.ID, OrgId: g.session.OrgId, User: g.session.User,
		Asset: g.session.Asset, Account: g.session.Account,
		Input: record.SQL, Output: output, Timestamp: record.StartedAt.Unix(),
		RiskLevel: risk, CmdAclId: record.ACLID, CmdGroupId: record.GroupID,
	}
	if err := p.replays.record(g, record.SQL, output, record.StartedAt); err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	if err := p.spool.put(envelope{Command: request}); err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	p.signal()
	return nil
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	for max > 0 && value[max]&0xc0 == 0x80 {
		max--
	}
	return value[:max]
}

func (p *WispProvider) enqueueLifecycle(id string, event protobuf.SessionLifecycleLogRequest_EventType, reason string) error {
	if err := p.spool.put(envelope{Lifecycle: &protobuf.SessionLifecycleLogRequest{SessionId: id, Event: event, Reason: reason}}); err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	p.signal()
	return nil
}

func (p *WispProvider) signal() {
	select {
	case p.wake <- struct{}{}:
	default:
	}
}

func (p *WispProvider) report(code string) {
	if p.options.OnError != nil {
		p.options.OnError(errors.New(code))
	}
}

var _ Provider = (*WispProvider)(nil)

type forwardResult struct {
	response *protobuf.ForwardResponse
	err      error
}

func (p *WispProvider) createForward(ctx context.Context, request *protobuf.ForwardRequest) (*protobuf.ForwardResponse, error) {
	// Do not cancel a resource-creating RPC merely because the browser canceled
	// the HTTP request. Observe its result under a separate bounded deadline,
	// then compensate any returned ID even if the caller has already gone away.
	requestCtx, cancel := p.rpcContext(context.WithoutCancel(ctx))
	results := make(chan forwardResult, 1)
	go func() {
		defer cancel()
		response, err := p.client.CreateForward(requestCtx, request)
		results <- forwardResult{response, err}
	}()
	select {
	case result := <-results:
		return result.response, result.err
	case <-ctx.Done():
		go func() {
			result := <-results
			if result.response != nil && result.response.Id != "" {
				if err := p.spool.put(envelope{Forward: &protobuf.ForwardDeleteRequest{Id: result.response.Id}}); err != nil {
					p.report("late_forward_cleanup_persistence_failed")
					return
				}
				p.signal()
			}
		}()
		return nil, ctx.Err()
	}
}

func authorizedDialect(data *protobuf.TokenAuthInfo) (string, error) {
	protocol := data.Asset.Protocols[0].Name
	tenant := ""
	if strings.EqualFold(data.GetPlatform().GetType(), "oceanbase") {
		protocol = "oceanbase"
		for _, p := range data.GetPlatform().GetProtocols() {
			if p != nil && (strings.EqualFold(p.Name, data.Asset.Protocols[0].Name) || len(data.GetPlatform().GetProtocols()) == 1) {
				tenant = p.GetSettings()["tenant_mode"]
				break
			}
		}
	}
	return CanonicalDialect(protocol, tenant)
}
