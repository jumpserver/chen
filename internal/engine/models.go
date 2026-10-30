package engine

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlanalysis"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type Options struct {
	// SingleMode accepts configured selectors instead of Core connection tokens.
	// It must only be enabled together with the local standalone provider.
	SingleMode     bool
	StateDir       string
	AllowedOrigins []string
	MaxSessions    int
	MaxConsoles    int
	MaxRows        int
	MaxResultBytes int
	QueryTimeout   time.Duration
}
type APIError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *APIError) Error() string { return e.Message }

type Permissions struct {
	Copy     bool `json:"can_copy"`
	Paste    bool `json:"can_paste"`
	Upload   bool `json:"can_upload"`
	Download bool `json:"can_download"`
}
type SessionView struct {
	ID          string      `json:"id"`
	Dialect     string      `json:"dialect"`
	Asset       string      `json:"asset"`
	Account     string      `json:"account"`
	Permissions Permissions `json:"permissions"`
	ExpiresAt   time.Time   `json:"expires_at"`
	State       string      `json:"state"`
	CanCancel   bool        `json:"can_cancel"`
	StateReason string      `json:"state_reason,omitempty"`
}
type ConsoleView struct {
	ID       string `json:"id"`
	Database string `json:"database"`
	Schema   string `json:"schema"`
	State    string `json:"state"`
}
type StatementResult struct {
	ResultID     string `json:"result_id,omitempty"`
	RowsAffected string `json:"rows_affected,omitempty"`
}
type ExecutionView struct {
	Plan             *PlanIdentity            `json:"plan,omitempty"`
	SQL              string                   `json:"sql,omitempty"` // server-generated browse SQL
	Edit             *EditOutcome             `json:"edit,omitempty"`
	ExportID         string                   `json:"export_id,omitempty"`
	ID               string                   `json:"id"`
	ConsoleID        string                   `json:"console_id"`
	Status           string                   `json:"status"`
	ResultIDs        []string                 `json:"result_ids"`
	StatementResults []StatementResult        `json:"statement_results"`
	Error            *APIError                `json:"error,omitempty"`
	AuditStatus      string                   `json:"audit_status"`
	StartedAt        *time.Time               `json:"started_at,omitempty"`
	FinishedAt       *time.Time               `json:"finished_at,omitempty"`
	Review           *jumpserver.ReviewStatus `json:"review,omitempty"`
}
type Column struct {
	Name     string `json:"name"`
	DataType string `json:"data_type"`
}
type Result struct {
	consoleID       string
	sourceAnalysis  *sqlanalysis.Statement
	sourceSQL       string
	browse          *editBrowse
	original        [][]any
	copyLossy       [][]bool // driver bytes rendered as hex instead of text
	masked          [][]bool
	rowRefs         []string
	editStale       bool // guarded by session.mu
	editSource      *sqlservice.EditableSource
	editSourceError string
	editSourceBound bool
	editDatabase    string
	memoryBytes     int
	ID              string   `json:"id"`
	Columns         []Column `json:"columns"`
	Rows            [][]any  `json:"rows"`
	Offset          int      `json:"offset"`
	TotalRows       int      `json:"total_rows"`
	Truncated       bool     `json:"truncated"`
	NextOffset      *int     `json:"next_offset,omitempty"`
}
type Event struct {
	Sequence uint64 `json:"sequence"`
	Type     string `json:"type"`
	Data     any    `json:"data"`
}

type execution struct {
	planMode        string         // immutable intent; original SQL stays in sql
	planSQL         string         // guarded by mu; omitted from small SSE snapshots
	planResult      *ExecutionPlan // guarded by mu, immutable after publication
	edit            *editPlan      // immutable plan, consumed under console lock
	browse          *editBrowse
	resultAnalysis  *sqlanalysis.Statement
	resultSQL       string     // worker-owned original SQL for result provenance
	export          *exportJob // immutable after queue publication
	mu              sync.RWMutex
	view            ExecutionView
	sql             string
	documentVersion int // immutable editor version bound at submission
	ctx             context.Context
	cancel          context.CancelFunc
	cancelDone      chan struct{}
	cancelOnce      sync.Once
	cancelRequested atomic.Bool
	deadlineExpired atomic.Bool
	submitReview    bool
	confirmWarnings bool
	deadlineTimer   *time.Timer
	deadlineAt      time.Time
	remaining       time.Duration
	deadlinePaused  bool
	policyWarnings  map[string]jumpserver.AuthorizationDecision // worker-owned; original SQL -> matched policy
}

func (e *execution) snapshot() ExecutionView {
	e.mu.RLock()
	defer e.mu.RUnlock()
	v := e.view
	if v.Plan != nil {
		identity := *v.Plan
		v.Plan = &identity
	}
	v.ResultIDs = append([]string{}, v.ResultIDs...)
	v.StatementResults = append([]StatementResult{}, v.StatementResults...)
	if v.Edit != nil {
		copy := *v.Edit
		v.Edit = &copy
	}
	if v.Review != nil {
		review := *v.Review
		review.Reviewers = append([]string{}, review.Reviewers...)
		v.Review = &review
	}
	return v
}
func terminal(s string) bool {
	return s == "succeeded" || s == "failed" || s == "canceled" || s == "unknown"
}

type console struct {
	id             string
	conn           *sql.Conn
	context        sqlservice.Context
	mu             sync.Mutex // physical connection: execution, metadata and context are mutually exclusive
	queue          chan *execution
	ctx            context.Context
	cancel         context.CancelFunc
	lost           atomic.Bool
	closed         atomic.Bool
	requests       map[string]*execution // protected by session.mu
	backendID      int64
	ownedDB        *sql.DB
	catalogAuditAt map[string]time.Time // protected by console.mu
}
type session struct {
	editPlans      map[string]*editPlan // protected by mu; bounded, never client-owned SQL
	exports        map[string]*exportJob
	exportRequests map[string]*exportJob
	aiTasks        map[string]*aiTask // protected by mu; bounded and canceled with the session
	catalog        *catalogResource   // protected by mu; distinct from SQL/control pools
	catalogOps     sync.WaitGroup     // Add under mu before close; includes audit completion
	workers        sync.WaitGroup     // includes closed/deleted consoles until accepted work is audited
	id             string
	grant          *jumpserver.Grant
	db             *sql.DB
	ctx            context.Context
	cancel         context.CancelFunc
	mu             sync.Mutex
	consoles       map[string]*console
	executions     map[string]*execution
	results        map[string]*Result
	resultBytes    int
	history        []Event
	sequence       uint64
	subscribers    map[uint64]chan Event
	nextSubscriber uint64
	closed         bool
}

func (s *session) view() SessionView {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.viewLocked()
}
func (s *session) viewLocked() SessionView {
	state := "ready"
	reason := ""
	if s.closed {
		state = "closed"
		reason = "session_closed"
	} else if err := s.grant.Check(); err != nil {
		state = "locked"
		reason = err.Error()
	}
	p := s.grant.Permissions
	return SessionView{s.id, s.grant.Config.Dialect, s.grant.AssetName, s.grant.AccountName, Permissions{p.Copy, p.Paste, p.Upload, p.Download}, s.grant.ExpiresAt, state, supportsActiveCancellation(s.grant.Config.Dialect), reason}
}
func (s *session) emit(kind string, data any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sequence++
	ev := Event{s.sequence, kind, data}
	s.history = append(s.history, ev)
	if len(s.history) > 256 {
		s.history = s.history[len(s.history)-256:]
	}
	for id, ch := range s.subscribers {
		select {
		case ch <- ev:
		default:
			close(ch)
			delete(s.subscribers, id)
		}
	}
}
func tokenKey(token string) [32]byte { return sha256.Sum256([]byte(token)) }
func newID(prefix string) string {
	var b [24]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return prefix + hex.EncodeToString(b[:])
}

type creation struct {
	done       chan struct{}
	token      string
	session    *session
	err        error
	requestKey [32]byte // hash of the browser's stable, random create nonce
	// Completed failures and closed-session tombstones retain only a bounded
	// idempotency outcome. Active sessions keep their creation entry until close.
	expiresAt time.Time
}
type Server struct {
	exportDir     string
	exportLock    *os.File
	exportInitErr error
	exportSlots   chan struct{}
	downloads     map[[32]byte]downloadTicket // protected by mu; only hashed one-use capabilities
	catalogSlots  chan struct{}               // global catalog-only concurrency limit
	provider      jumpserver.Provider
	opts          Options
	mu            sync.RWMutex
	sessions      map[[32]byte]*session
	creations     map[[32]byte]*creation
	ctx           context.Context
	cancel        context.CancelFunc
	creating      sync.WaitGroup
	cleanup       sync.WaitGroup
	cleanupErr    error // protected by mu
	shutdownOnce  sync.Once
	shutdownDone  chan struct{}
}
