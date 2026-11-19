package engine

import (
	"context"
	"crypto/tls"
	"database/sql"
	"database/sql/driver"
	"errors"
	"net"
	"strings"
	"sync/atomic"
	"time"

	db2 "github.com/go-db2/go-db2"
	"github.com/jumpserver/chen/internal/jumpserver"
)

func openDB2(c jumpserver.ConnectionConfig, t *tls.Config) (*sql.DB, error) {
	cfg, err := db2Config(c, t)
	if err != nil {
		return nil, err
	}
	return sql.OpenDB(&db2Connector{Connector: db2.NewConnector(cfg)}), nil
}

func db2Config(c jumpserver.ConnectionConfig, t *tls.Config) (*db2.Config, error) {
	// DRDA's RDBNAM field is 18 bytes; the driver otherwise truncates it.
	if c.Host == "" || c.Database == "" || len(c.Database) > 18 || c.Port < 1 || c.Port > 65535 {
		return nil, errors.New("invalid Db2 connection configuration")
	}
	for _, value := range []string{c.Host, c.Database, c.User, c.Password} {
		if strings.ContainsRune(value, 0) {
			return nil, errors.New("invalid Db2 connection configuration")
		}
	}
	cfg := db2.NewConfig()
	cfg.Host, cfg.Port, cfg.Database = c.Host, c.Port, c.Database
	// The pinned driver's dialer formats host:port directly.
	if net.ParseIP(c.Host) != nil && strings.Contains(c.Host, ":") {
		cfg.Host = "[" + c.Host + "]"
	}
	cfg.User, cfg.Password = c.User, c.Password
	cfg.Timeout = time.Minute
	cfg.ClientApplName = "Chen Go"
	if t != nil {
		cfg.UseSSL, cfg.TLSConfig = true, t.Clone()
	} else {
		// The trusted Core/single configuration explicitly selected plain TCP.
		// Credentials are assigned directly, never interpolated into a DSN.
		cfg.AllowPlaintextAuth = true
	}
	return cfg, nil
}

type db2Connector struct{ *db2.Connector }

func (c *db2Connector) Connect(ctx context.Context) (driver.Conn, error) {
	connect, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	raw, err := c.Connector.Connect(connect)
	if err != nil {
		return nil, err
	}
	return &db2Conn{Conn: raw.(*db2.Conn)}, nil
}

// Keep transaction ownership and original server cancellation diagnostics on the
// same physical connection. database/sql can replace Rows.Err with ctx.Err.
type db2Conn struct {
	*db2.Conn
	inTransaction atomic.Bool
	uncertain     atomic.Bool
	cancellations atomic.Uint64
}

func (c *db2Conn) ChenTransactionState() string {
	if c.uncertain.Load() || !c.Conn.IsValid() {
		return string(editTxUnknown)
	}
	if c.inTransaction.Load() {
		return string(editTxActive)
	}
	return string(editTxIdle)
}
func (c *db2Conn) ChenCancellationSequence() uint64 { return c.cancellations.Load() }

func (c *db2Conn) IsValid() bool { return !c.uncertain.Load() && c.Conn.IsValid() }

func (c *db2Conn) ResetSession(ctx context.Context) error {
	if c.uncertain.Load() {
		return driver.ErrBadConn
	}
	return c.Conn.ResetSession(ctx)
}

func (c *db2Conn) observe(err error) error {
	if db2ServerCancellation(err) {
		c.cancellations.Add(1)
	}
	return err
}
func (c *db2Conn) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}
func (c *db2Conn) BeginTx(ctx context.Context, opts driver.TxOptions) (driver.Tx, error) {
	tx, err := c.Conn.BeginTx(ctx, opts)
	if err != nil {
		return nil, c.observe(err)
	}
	c.inTransaction.Store(true)
	return &db2Tx{Tx: tx, conn: c}, nil
}

type db2Tx struct {
	driver.Tx
	conn *db2Conn
}

func (t *db2Tx) finish(err error) error {
	if err != nil {
		t.conn.uncertain.Store(true)
	} else {
		t.conn.inTransaction.Store(false)
	}
	return err
}
func (t *db2Tx) Commit() error   { return t.finish(t.Tx.Commit()) }
func (t *db2Tx) Rollback() error { return t.finish(t.Tx.Rollback()) }
func (c *db2Conn) Prepare(query string) (driver.Stmt, error) {
	return c.PrepareContext(context.Background(), query)
}
func (c *db2Conn) PrepareContext(ctx context.Context, query string) (driver.Stmt, error) {
	stmt, err := c.Conn.PrepareContext(ctx, query)
	if err != nil {
		return nil, c.observe(err)
	}
	return &db2Stmt{Stmt: stmt.(*db2.Stmt), conn: c}, nil
}
func (c *db2Conn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	result, err := c.Conn.ExecContext(ctx, query, args)
	return result, c.observe(err)
}
func (c *db2Conn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	rows, err := c.Conn.QueryContext(ctx, query, args)
	if err != nil {
		return nil, c.observe(err)
	}
	return &db2Rows{Rows: rows.(*db2.Rows), conn: c}, nil
}

type db2Stmt struct {
	*db2.Stmt
	conn *db2Conn
}

func (s *db2Stmt) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	result, err := s.Stmt.ExecContext(ctx, args)
	return result, s.conn.observe(err)
}
func (s *db2Stmt) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	rows, err := s.Stmt.QueryContext(ctx, args)
	if err != nil {
		return nil, s.conn.observe(err)
	}
	return &db2Rows{Rows: rows.(*db2.Rows), conn: s.conn}, nil
}

type db2Rows struct {
	*db2.Rows
	conn *db2Conn
}

func (r *db2Rows) Next(values []driver.Value) error { return r.conn.observe(r.Rows.Next(values)) }
func (r *db2Rows) NextResultSet() error             { return r.conn.observe(r.Rows.NextResultSet()) }
func db2DatabaseError(err error) *APIError {
	if db2UncertainNativeError(err) {
		return nil
	}
	var native *db2.Error
	if errors.As(err, &native) {
		return &APIError{"DB2_SQL_ERROR", native.Error()}
	}
	return nil
}
