// Package jumpserver defines Chen's server-side connection authorization
// contract. Managed deployments obtain credentials from Core through Wisp;
// standalone deployments supply private, operator-managed configuration.
package jumpserver

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

type Provider interface {
	Open(context.Context, string, string) (*Grant, error)
	Connected(context.Context, *Grant) error
	Authorize(context.Context, *Grant, string) error
	Record(context.Context, *Grant, AuditRecord) error
	Close(context.Context, *Grant) error
	Watch(context.Context, *Grant, func(Control))
	Mask(*Grant, string, string, any) (any, bool)
}

// ConnectionConfig is server-only. Even accidental JSON encoding must not reveal
// credentials, keys or the database address to the browser.
type ConnectionConfig struct {
	Dialect   string    `json:"-"`
	Host      string    `json:"-"`
	Port      int       `json:"-"`
	User      string    `json:"-"`
	Password  string    `json:"-"`
	Database  string    `json:"-"`
	Schema    string    `json:"-"`
	Transport string    `json:"-"`
	TLS       TLSConfig `json:"-"`
}

type TLSConfig struct {
	Enabled            bool   `json:"-"`
	ServerName         string `json:"-"`
	InsecureSkipVerify bool   `json:"-"`
	Mode               string `json:"-"`
	CACert             string `json:"-"`
	ClientCert         string `json:"-"`
	ClientKey          string `json:"-"`
}

type Permissions struct {
	Connect  bool `json:"connect"`
	Upload   bool `json:"upload"`
	Download bool `json:"download"`
	Copy     bool `json:"copy"`
	Paste    bool `json:"paste"`
}

type Grant struct {
	ID          string           `json:"id"`
	OwnerID     string           `json:"owner_id"`
	OwnerName   string           `json:"owner_name"`
	AssetID     string           `json:"asset_id"`
	AssetName   string           `json:"asset_name"`
	AccountName string           `json:"account_name"`
	Config      ConnectionConfig `json:"-"`
	Permissions Permissions      `json:"permissions"`
	ExpiresAt   time.Time        `json:"expires_at"`
	MaxIdle     time.Duration    `json:"-"`
	MaxDuration time.Duration    `json:"-"`

	mu               sync.Mutex
	session          *protobuf.Session
	matchers         []commandMatcher
	maskers          []maskRule
	gatewayID        string
	created          time.Time
	lastActive       time.Time
	busy             int
	locked           bool
	closed           bool
	connected        bool
	closeReason      string
	dynamicEnd       time.Time
	watchers         map[uint64]chan Control
	nextWatcher      uint64
	closeMu          sync.Mutex
	closePersisted   bool
	terminationOwned bool // engine must stop database work before remote cleanup
}

var (
	ErrClosed    = errors.New("session_closed")
	ErrLocked    = errors.New("session_locked")
	ErrExpired   = errors.New("permission_expired")
	ErrIdle      = errors.New("idle_disconnect")
	ErrDuration  = errors.New("max_session_timeout")
	ErrRevoked   = errors.New("permission_revoked")
	ErrAuditDisk = errors.New("audit_persistence_failed")
)

// NewLocalGrant initializes the same connection lifetime accounting used by
// managed grants, without a Core session or a Wisp client. Identity display
// fields may be set by the caller before publishing the returned grant.
func NewLocalGrant(id string, config ConnectionConfig, permissions Permissions, maxIdle, maxDuration time.Duration) (*Grant, error) {
	if id == "" || maxIdle <= 0 || maxDuration <= 0 || maxIdle > maxDuration {
		return nil, errors.New("invalid_local_grant_lifetime")
	}
	now := time.Now()
	return &Grant{ID: id, Config: config, Permissions: permissions, ExpiresAt: now.Add(maxDuration), MaxIdle: maxIdle, MaxDuration: maxDuration, created: now, lastActive: now, watchers: make(map[uint64]chan Control)}, nil
}

// CloseLocal revokes a local grant immediately. The standalone resource owner
// remains responsible for persisting its close audit before forgetting identity.
func (g *Grant) CloseLocal() {
	if g == nil {
		return
	}
	g.mu.Lock()
	g.closed = true
	g.mu.Unlock()
}

// Check is intentionally independent of the browser connection. Call it before
// all operations, including metadata and result reads, not only SQL execution.
func (g *Grant) Check() error {
	if g == nil {
		return ErrClosed
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.checkLocked(time.Now())
}

func (g *Grant) checkLocked(now time.Time) error {
	switch {
	case g.closed:
		return ErrClosed
	case !g.ExpiresAt.IsZero() && !now.Before(g.ExpiresAt):
		return ErrExpired
	case !g.dynamicEnd.IsZero() && !now.Before(g.dynamicEnd):
		return ErrRevoked
	case g.MaxDuration > 0 && !now.Before(g.created.Add(g.MaxDuration)):
		return ErrDuration
	case g.MaxIdle > 0 && g.busy == 0 && now.Sub(g.lastActive) >= g.MaxIdle:
		return ErrIdle
	case g.locked:
		return ErrLocked
	default:
		return nil
	}
}

func (g *Grant) Touch() {
	g.mu.Lock()
	g.lastActive = time.Now()
	g.mu.Unlock()
}

// SetBusy brackets database work. Completion counts as activity so a long query
// does not immediately trip the idle limit when it finishes.
func (g *Grant) SetBusy(busy bool) {
	g.mu.Lock()
	if busy {
		g.busy++
	} else {
		if g.busy > 0 {
			g.busy--
		}
		g.lastActive = time.Now()
	}
	g.mu.Unlock()
}

type AuditRecord struct {
	ID         string
	SQL        string
	Output     string
	Error      string
	StartedAt  time.Time
	FinishedAt time.Time
	RiskLevel  int32
	ACLID      string
	GroupID    string
}

type Control struct {
	Type   string `json:"type"`
	Reason string `json:"reason,omitempty"`
	Actor  string `json:"actor,omitempty"`
}

// PolicyError carries audit classification even though the SQL was not run.
type PolicyError struct {
	Code      string
	RiskLevel int32
	ACLID     string
	GroupID   string
}

func (e *PolicyError) Error() string { return e.Code }
