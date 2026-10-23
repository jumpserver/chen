package engine

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgconn/ctxwatch"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/jumpserver/chen/internal/jumpserver"
)

func makeTLS(c jumpserver.ConnectionConfig) (*tls.Config, error) {
	if !c.TLS.Enabled {
		return nil, nil
	}
	mode := strings.ToLower(strings.TrimSpace(c.TLS.Mode))
	if c.Dialect != "postgresql" {
		mode = ""
	}
	switch mode {
	case "", "require", "verify-ca", "verify-full":
	case "disable":
		return nil, nil
	default:
		return nil, errors.New("unsupported database TLS mode; select require, verify-ca or verify-full")
	}
	t := &tls.Config{MinVersion: tls.VersionTLS12, ServerName: c.TLS.ServerName, InsecureSkipVerify: c.TLS.InsecureSkipVerify}
	if t.ServerName == "" {
		t.ServerName = c.Host
	}
	if c.TLS.CACert != "" {
		pool, err := x509.SystemCertPool()
		if err != nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM([]byte(c.TLS.CACert)) {
			return nil, errors.New("invalid database CA certificate")
		}
		t.RootCAs = pool
	}
	if mode == "verify-full" || mode == "verify-ca" {
		if c.TLS.InsecureSkipVerify {
			return nil, errors.New("certificate verification mode conflicts with allow-invalid-certificate")
		}
		t.InsecureSkipVerify = false
	}
	// libpq require guarantees encryption; a supplied root CA also verifies the
	// chain, without hostname validation. verify-full uses Go's normal hostname
	// and chain verification. No plaintext fallback is configured.
	if mode == "verify-ca" || (mode == "require" && c.TLS.CACert != "") {
		t.InsecureSkipVerify = true
		t.VerifyConnection = func(state tls.ConnectionState) error {
			if len(state.PeerCertificates) == 0 {
				return errors.New("database certificate missing")
			}
			intermediates := x509.NewCertPool()
			for _, cert := range state.PeerCertificates[1:] {
				intermediates.AddCert(cert)
			}
			_, err := state.PeerCertificates[0].Verify(x509.VerifyOptions{Roots: t.RootCAs, Intermediates: intermediates})
			return err
		}
	} else if mode == "require" {
		t.InsecureSkipVerify = true
	}
	if c.TLS.ClientCert != "" || c.TLS.ClientKey != "" {
		cert, err := tls.X509KeyPair([]byte(c.TLS.ClientCert), []byte(c.TLS.ClientKey))
		if err != nil {
			return nil, errors.New("invalid database client certificate")
		}
		t.Certificates = []tls.Certificate{cert}
	}
	return t, nil
}
func openDatabase(c jumpserver.ConnectionConfig, max int) (*sql.DB, error) {
	t, err := makeTLS(c)
	if err != nil {
		return nil, err
	}
	var db *sql.DB
	switch strings.ToLower(c.Dialect) {
	case "mysql", "mariadb":
		cfg := mysql.NewConfig()
		cfg.Net = "tcp"
		cfg.Addr = net.JoinHostPort(c.Host, strconv.Itoa(c.Port))
		cfg.User = c.User
		cfg.Passwd = c.Password
		cfg.DBName = c.Database
		// Keep MySQL date/time text, including zero dates and timezone-free DATETIME.
		cfg.ParseTime = false
		cfg.Loc = time.UTC
		cfg.Timeout = 10 * time.Second
		cfg.ReadTimeout = 2 * time.Minute
		cfg.WriteTimeout = 15 * time.Second
		cfg.TLS = t
		connector, e := mysql.NewConnector(cfg)
		if e != nil {
			return nil, errors.New("invalid MySQL connection configuration")
		}
		db = sql.OpenDB(connector)
	case "postgresql":
		if os.Getenv("PGSERVICE") != "" {
			return nil, errors.New("PGSERVICE cannot override server-managed database connections")
		}
		cfg, e := pgx.ParseConfig("host=127.0.0.1 port=5432 user=chen password='' dbname=chen sslmode=disable sslrootcert='' sslcert='' sslkey='' sslpassword='' sslnegotiation=postgres target_session_attrs=any connect_timeout=10")
		if e != nil {
			return nil, errors.New("invalid PostgreSQL connection configuration")
		}
		cfg.Host = c.Host
		cfg.Port = uint16(c.Port)
		cfg.User = c.User
		cfg.Password = c.Password
		cfg.Database = c.Database
		if cfg.Database == "" {
			cfg.Database = "postgres"
		}
		cfg.TLSConfig = t
		cfg.Fallbacks = nil
		cfg.SSLNegotiation = "postgres"
		cfg.ConnectTimeout = 10 * time.Second
		cfg.RuntimeParams = map[string]string{"application_name": "Chen Go"}
		cfg.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol
		cfg.BuildContextWatcherHandler = func(conn *pgconn.PgConn) ctxwatch.Handler {
			return &pgconn.CancelRequestContextWatcherHandler{Conn: conn, CancelRequestDelay: 0, DeadlineDelay: 3 * time.Second}
		}
		db = stdlib.OpenDB(*cfg)
	default:
		db, err = extraDatabase(c, t)
		if err != nil {
			return nil, err
		}
	}
	db.SetMaxOpenConns(max)
	db.SetMaxIdleConns(1)
	db.SetConnMaxIdleTime(5 * time.Minute)
	return db, nil
}
func safeDatabaseError(err error) *APIError {
	if err == nil {
		return nil
	}
	// Driver errors are useful SQL diagnostics; connection strings and network
	// errors are never reflected. SQL text is already owned by this session.
	var me *mysql.MySQLError
	if errors.As(err, &me) {
		return &APIError{fmt.Sprintf("MYSQL_%d", me.Number), me.Message}
	}
	var pe *pgconn.PgError
	if errors.As(err, &pe) {
		return &APIError{pe.Code, pe.Message}
	}
	if api := extraDatabaseError(err); api != nil {
		return api
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return &APIError{"query_timeout", "Query deadline exceeded"}
	}
	if errors.Is(err, context.Canceled) {
		return &APIError{"query_canceled", "Query cancellation requested"}
	}
	return &APIError{"database_error", "Database operation failed; the connection may no longer be usable"}
}
