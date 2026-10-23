package engine

import (
	"context"
	"database/sql"
	"errors"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

func connectionContext(ctx context.Context, conn *sql.Conn, dialect string, configured sqlservice.Context) (sqlservice.Context, error) {
	current := configured
	var err error
	switch {
	case jumpserver.IsMySQLDialect(dialect):
		err = conn.QueryRowContext(ctx, "SELECT COALESCE(DATABASE(),'')").Scan(&current.Database)
		current.Schema = current.Database
	case dialect == "postgresql":
		err = conn.QueryRowContext(ctx, "SELECT current_database(), COALESCE(current_schema(),'')").Scan(&current.Database, &current.Schema)
	case dialect == "sqlserver":
		err = conn.QueryRowContext(ctx, "SELECT DB_NAME(), COALESCE(SCHEMA_NAME(),'dbo')").Scan(&current.Database, &current.Schema)
	case dialect == "clickhouse":
		err = conn.QueryRowContext(ctx, "SELECT currentDatabase()").Scan(&current.Database)
		current.Schema = current.Database
	case dialect == "db2":
		err = conn.QueryRowContext(ctx, "VALUES (CURRENT SERVER, CURRENT SCHEMA)").Scan(&current.Database, &current.Schema)
	case dialect == "oracle" || dialect == "dameng" || dialect == "oceanbase-oracle":
		err = conn.QueryRowContext(ctx, "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL").Scan(&current.Schema)
		if current.Database == "" {
			current.Database = current.Schema
		}
	default:
		return current, errors.New("unsupported database context")
	}
	return current, err
}

func initializeConsole(ctx context.Context, conn *sql.Conn, cfg jumpserver.ConnectionConfig, requested sqlservice.Context) (sqlservice.Context, int64, error) {
	var err error
	if requested.Database == "" {
		requested.Database = cfg.Database
	}
	if requested.Schema == "" {
		requested.Schema = cfg.Schema
	}
	if requested.Database != "" {
		switch {
		case jumpserver.IsMySQLDialect(cfg.Dialect) || cfg.Dialect == "sqlserver" || cfg.Dialect == "clickhouse":
			_, err = conn.ExecContext(ctx, "USE "+quoteDatabaseIdentifier(requested.Database, cfg.Dialect))
		case cfg.Dialect != "postgresql" && requested.Database != cfg.Database:
			return requested, 0, unsupportedContext(cfg.Dialect)
		}
	}
	if err == nil && requested.Schema != "" {
		quoted := quoteDatabaseIdentifier(requested.Schema, cfg.Dialect)
		switch cfg.Dialect {
		case "postgresql":
			_, err = conn.ExecContext(ctx, "SET search_path TO "+quoted)
		case "oracle", "oceanbase-oracle":
			_, err = conn.ExecContext(ctx, "ALTER SESSION SET CURRENT_SCHEMA = "+quoted)
		case "dameng":
			_, err = conn.ExecContext(ctx, "SET SCHEMA "+quoted)
		case "db2":
			_, err = conn.ExecContext(ctx, "SET CURRENT SCHEMA "+quoted)
			// SQL Server's default schema belongs to the database principal. A UI
			// context is not allowed to ALTER USER merely to browse another schema.
		}
	}
	if err != nil {
		return requested, 0, err
	}
	current, err := connectionContext(ctx, conn, cfg.Dialect, requested)
	var backend int64
	if err == nil {
		switch {
		case jumpserver.IsMySQLDialect(cfg.Dialect):
			err = conn.QueryRowContext(ctx, "SELECT CONNECTION_ID()").Scan(&backend)
		case cfg.Dialect == "oceanbase-oracle":
			err = conn.QueryRowContext(ctx, "SELECT USERENV('SID') FROM DUAL").Scan(&backend)
		case cfg.Dialect == "postgresql":
			err = conn.QueryRowContext(ctx, "SELECT pg_backend_pid()").Scan(&backend)
		}
	}
	return current, backend, err
}
