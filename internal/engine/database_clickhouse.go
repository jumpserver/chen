package engine

import (
	"context"
	"database/sql/driver"
	"errors"
	"net/http"
	"sync/atomic"

	"github.com/ClickHouse/clickhouse-go/v2"
	"github.com/jumpserver/chen/internal/sqlservice"
)

// ClickHouse HTTP sends a database URL parameter on every request. Java's
// JDBC driver updates that client-side selection after a successful USE;
// clickhouse-go leaves it fixed at login. Keep the selection per physical
// connection so consoles and the independent catalog cannot affect each other.
type clickHouseHTTPConnector struct{ options clickhouse.Options }

func (c *clickHouseHTTPConnector) Driver() driver.Driver {
	return clickhouse.Connector(&c.options).Driver()
}

func (c *clickHouseHTTPConnector) Connect(ctx context.Context) (driver.Conn, error) {
	options := c.options
	database := &atomic.Value{}
	database.Store(options.Auth.Database)
	transport := options.TransportFunc
	options.TransportFunc = func(base *http.Transport) (http.RoundTripper, error) {
		var next http.RoundTripper = base
		if transport != nil {
			var err error
			next, err = transport(base)
			if err != nil {
				return nil, err
			}
		}
		return &clickHouseDatabaseTransport{next: next, database: database}, nil
	}
	raw, err := clickhouse.Connector(&options).Connect(ctx)
	if err != nil {
		return nil, err
	}
	conn, ok := raw.(clickHouseHTTPDriver)
	if !ok {
		_ = raw.Close()
		return nil, errors.New("unsupported ClickHouse HTTP driver interfaces")
	}
	return &clickHouseHTTPConn{clickHouseHTTPDriver: conn, database: database}, nil
}

// Forward the pinned driver's optional interfaces, including named value and
// batch handling, while changing only successful literal USE execution.
type clickHouseHTTPDriver interface {
	driver.Conn
	driver.ConnBeginTx
	driver.ConnPrepareContext
	driver.ExecerContext
	driver.QueryerContext
	driver.NamedValueChecker
	driver.Pinger
	driver.SessionResetter
}

type clickHouseHTTPConn struct {
	clickHouseHTTPDriver
	database *atomic.Value
}

func (c *clickHouseHTTPConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	result, err := c.clickHouseHTTPDriver.ExecContext(ctx, query, args)
	if err == nil {
		if database, ok := sqlservice.ClickHouseUseDatabase(query); ok {
			c.database.Store(database)
		}
	}
	return result, err
}

type clickHouseDatabaseTransport struct {
	next     http.RoundTripper
	database *atomic.Value
}

func (t *clickHouseDatabaseTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	current := request.Clone(request.Context())
	query := current.URL.Query()
	query.Set("database", t.database.Load().(string))
	current.URL.RawQuery = query.Encode()
	return t.next.RoundTrip(current)
}

func (t *clickHouseDatabaseTransport) CloseIdleConnections() {
	if closer, ok := t.next.(interface{ CloseIdleConnections() }); ok {
		closer.CloseIdleConnections()
	}
}
