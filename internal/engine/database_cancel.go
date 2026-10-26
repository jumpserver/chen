package engine

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/ClickHouse/clickhouse-go/v2"
)

func supportsActiveCancellation(dialect string) bool {
	switch dialect {
	case "mysql", "mariadb", "postgresql", "sqlserver", "oracle", "clickhouse", "oceanbase-mysql", "oceanbase-oracle":
		return true
	default:
		return false
	}
}

// go-mssqldb v1.11.2 tokenProcessor.next sends TDS attention and drains
// the server acknowledgement before returning ctx.Err. Failed acknowledgements
// return a distinct cancelDrainError; other drivers do not share this guarantee.
// Only pass the direct QueryContext/ExecContext error: database/sql can replace
// Rows.Err with the context error independently of a driver's acknowledgement.
func driverConfirmedCancellation(dialect string, err error) bool {
	return dialect == "sqlserver" && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded))
}

func executionContext(ctx context.Context, dialect, id string) context.Context {
	if dialect == "clickhouse" {
		return clickhouse.Context(ctx, clickhouse.WithQueryID(id))
	}
	return ctx
}

// cancelDriverExecution uses a separate connection and only targets this
// server-generated execution ID under the current database account.
func cancelDriverExecution(ctx context.Context, s *session, c *console, e *execution) {
	if s.db == nil || e == nil || s.grant.Config.Dialect != "clickhouse" {
		return
	}
	_ = cancelClickHouseExecution(ctx, s.db, e.view.ID)
}

func cancelClickHouseExecution(ctx context.Context, db *sql.DB, id string) error {
	rows, err := db.QueryContext(ctx, "KILL QUERY WHERE query_id = ? AND user = currentUser() SYNC", id)
	if err != nil {
		return err
	}
	defer rows.Close()
	columns, err := rows.Columns()
	if err != nil {
		return err
	}
	values := make([]any, len(columns))
	targets := make([]any, len(columns))
	for i := range values {
		targets[i] = &values[i]
	}
	for rows.Next() {
		if err := rows.Scan(targets...); err != nil {
			return err
		}
		for i, name := range columns {
			if name == "kill_status" && fmt.Sprint(values[i]) != "finished" {
				return fmt.Errorf("ClickHouse cancellation unconfirmed: %v", values[i])
			}
		}
	}
	return rows.Err()
}
