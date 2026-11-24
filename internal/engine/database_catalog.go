package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// Druid configured 7,200,000 ms on the Java backend's JDBC connections.
const catalogSocketTimeout = 120 * time.Minute

func openDriverPool(connector driver.Connector, catalog bool, loginTimeout time.Duration) *sql.DB {
	if catalog {
		connector = catalogLoginConnector{connector, loginTimeout}
	}
	return sql.OpenDB(connector)
}

type catalogLoginConnector struct {
	driver.Connector
	timeout time.Duration
}

type catalogLoginKey struct{}
type catalogLogin struct {
	ctx      context.Context
	finished atomic.Bool
}

func (c catalogLoginConnector) Connect(ctx context.Context) (driver.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	login := &catalogLogin{ctx: ctx}
	conn, err := c.Connector.Connect(context.WithValue(ctx, catalogLoginKey{}, login))
	login.finished.Store(true)
	if ctx.Err() != nil {
		if err == nil {
			_ = conn.Close()
		}
		return nil, ctx.Err()
	}
	return conn, err
}

func watchCatalogLogin(ctx context.Context, conn net.Conn) {
	if login, ok := ctx.Value(catalogLoginKey{}).(*catalogLogin); ok {
		// Some drivers perform blocking socket reads during authentication.
		// Closing the transport interrupts them even before query cancellation is available.
		context.AfterFunc(login.ctx, func() {
			if !login.finished.Load() {
				_ = conn.Close()
			}
		})
	}
}

type catalogSocketDialer struct {
	dial         func(context.Context, string, string) (net.Conn, error)
	readTimeout  time.Duration
	writeTimeout time.Duration
	http         bool
}

func (d catalogSocketDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	conn, err := d.dial(ctx, network, address)
	if err != nil {
		return nil, err
	}
	watchCatalogLogin(ctx, conn)
	readTimeout := d.readTimeout
	if readTimeout == 0 {
		readTimeout = catalogSocketTimeout
	}
	return &catalogSocket{
		Conn: conn, read: socketDeadline{timeout: readTimeout},
		write: socketDeadline{timeout: d.writeTimeout}, http: d.http,
	}, nil
}

// Keep the earlier of the current socket operation's deadline and any explicit
// deadline from TLS, driver cancellation, or the caller. A new Read renews the
// socket timer even if a query has already streamed data for over two hours.
type socketDeadline struct {
	mu       sync.Mutex
	timeout  time.Duration
	explicit time.Time
	idle     time.Time
}

func (d *socketDeadline) renew(set func(time.Time) error) error {
	if d.timeout == 0 {
		return nil
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	d.idle = time.Now().Add(d.timeout)
	return d.apply(set)
}

func (d *socketDeadline) set(at time.Time, set func(time.Time) error) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.explicit = at
	return d.apply(set)
}

func (d *socketDeadline) apply(set func(time.Time) error) error {
	at := d.explicit
	if !d.idle.IsZero() && (at.IsZero() || d.idle.Before(at)) {
		at = d.idle
	}
	return set(at)
}

type catalogSocket struct {
	net.Conn
	read, write socketDeadline
	http        bool
}

func (c *catalogSocket) Read(p []byte) (int, error) {
	if err := c.read.renew(c.Conn.SetReadDeadline); err != nil {
		return 0, err
	}
	return c.Conn.Read(p)
}

func (c *catalogSocket) Write(p []byte) (int, error) {
	if c.http {
		// net/http keeps a Read pending on pooled idle connections. Start a
		// fresh wait when it sends a new request on the same connection.
		if err := c.read.renew(c.Conn.SetReadDeadline); err != nil {
			return 0, err
		}
	}
	if err := c.write.renew(c.Conn.SetWriteDeadline); err != nil {
		return 0, err
	}
	return c.Conn.Write(p)
}

func (c *catalogSocket) SetReadDeadline(t time.Time) error {
	return c.read.set(t, c.Conn.SetReadDeadline)
}

func (c *catalogSocket) SetWriteDeadline(t time.Time) error {
	return c.write.set(t, c.Conn.SetWriteDeadline)
}

func (c *catalogSocket) SetDeadline(t time.Time) error {
	return errors.Join(c.SetReadDeadline(t), c.SetWriteDeadline(t))
}
