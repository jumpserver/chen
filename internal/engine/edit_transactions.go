package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"reflect"
	"strings"

	"github.com/jackc/pgx/v5/stdlib"
)

type editTxState string

const (
	editTxIdle    editTxState = "idle"
	editTxActive  editTxState = "active"
	editTxFailed  editTxState = "failed"
	editTxUnknown editTxState = "unknown"
)

// Read protocol/driver transaction state while sql.Conn.Raw holds the physical
// connection. No unsafe pointers or writes to driver internals are used. Private
// scalar fields are version-pinned and fail closed when their shape changes.
func editTransactionState(ctx context.Context, c *console, dialect string) (editTxState, error) {
	state := editTxUnknown
	if dialect == "sqlserver" {
		var count, status, implicit int
		err := c.conn.QueryRowContext(ctx, "SELECT @@TRANCOUNT, XACT_STATE(), @@OPTIONS & 2").Scan(&count, &status, &implicit)
		if err != nil {
			return state, err
		}
		if status < 0 {
			return editTxFailed, nil
		}
		if count > 0 && status == 1 {
			return editTxActive, nil
		}
		// XACT_STATE may report the probe SELECT's own auto-commit unit of
		// work. With no BEGIN nesting and IMPLICIT_TRANSACTIONS off there is
		// no user transaction to preserve after this statement completes.
		if count == 0 && status >= 0 && implicit == 0 {
			return editTxIdle, nil
		}
		return state, nil
	}
	if dialect == "oracle" {
		var id sql.NullString
		// The argument defaults to FALSE. SQL on Oracle 12/19 cannot use the
		// PL/SQL BOOLEAN literal FALSE as an argument (ORA-00904).
		if err := c.conn.QueryRowContext(ctx, "SELECT DBMS_TRANSACTION.LOCAL_TRANSACTION_ID FROM DUAL").Scan(&id); err != nil {
			return state, err
		}
		if id.Valid && id.String != "" {
			return state, nil
		}
	}
	err := c.conn.Raw(func(raw any) error {
		if wrapped, ok := raw.(*damengConn); ok {
			raw = wrapped.damengContextConn
		}
		if pg, ok := raw.(*stdlib.Conn); ok {
			switch pg.Conn().PgConn().TxStatus() {
			case 'I':
				state = editTxIdle
			case 'T':
				state = editTxActive
			case 'E':
				state = editTxFailed
			}
			return nil
		}
		// Tests may supply a deterministic protocol probe through their driver.
		if probe, ok := raw.(interface{ ChenTransactionState() string }); ok {
			state = editTxState(probe.ChenTransactionState())
			return nil
		}
		rv := reflect.ValueOf(raw)
		if rv.Kind() != reflect.Pointer || rv.IsNil() {
			return nil
		}
		rv = rv.Elem()
		if rv.Kind() != reflect.Struct {
			return nil
		}
		typ := rv.Type()
		switch {
		case typ.PkgPath() == "github.com/go-sql-driver/mysql" || typ.PkgPath() == "github.com/oceanbase/go-oceanbase-driver":
			flags := rv.FieldByName("status")
			if !flags.IsValid() || !flags.CanUint() {
				return nil
			}
			f := flags.Uint()
			if f&1 != 0 {
				state = editTxActive
			} else if f&2 != 0 {
				state = editTxIdle
			}
		case typ.PkgPath() == "github.com/sijms/go-ora/v2":
			auto := rv.FieldByName("autoCommit")
			if auto.IsValid() && auto.Kind() == reflect.Bool && auto.Bool() {
				state = editTxIdle
			}
		case typ.PkgPath() == "gitee.com/chunanyong/dm":
			auto, finished := rv.FieldByName("autoCommit"), rv.FieldByName("trxFinish")
			if auto.IsValid() && finished.IsValid() && auto.Kind() == reflect.Bool && finished.Kind() == reflect.Bool {
				if finished.Bool() && auto.Bool() {
					state = editTxIdle
				} else if !finished.Bool() && !auto.Bool() {
					state = editTxActive
				}
			}
		case typ.PkgPath() == "github.com/ibmdb/go_ibm_db":
			// IBM CLI starts in SQL_AUTOCOMMIT_ON; its only transition to OFF
			// is Begin, which installs tx before returning. SQL text cannot set
			// the connection attribute. A live Tx remains user-owned.
			tx := rv.FieldByName("tx")
			if tx.IsValid() && tx.Kind() == reflect.Pointer {
				if tx.IsNil() {
					state = editTxIdle
				} else {
					state = editTxActive
				}
			}
		}
		return nil
	})
	return state, err
}

type editBoundary struct {
	conn               *sql.Conn
	tx                 *sql.Tx
	savepoint, dialect string
}

func beginEditBoundary(ctx context.Context, c *console, dialect string) (*editBoundary, error) {
	state, err := editTransactionState(ctx, c, dialect)
	if err != nil {
		return nil, editError("transaction_probe_failed", "Cannot verify transaction state; reconnect before saving")
	}
	if state == editTxFailed {
		return nil, editError("transaction_failed", "Roll back the failed transaction before saving")
	}
	if state != editTxIdle && state != editTxActive {
		return nil, editError("transaction_state_unknown", "Transaction state is unknown or manual-commit idle; reconnect before saving")
	}
	b := &editBoundary{conn: c.conn, dialect: dialect}
	if state == editTxIdle {
		b.tx, err = c.conn.BeginTx(context.WithoutCancel(ctx), nil)
		if err != nil {
			return nil, err
		}
		return b, nil
	}
	b.savepoint = "chen_edit_" + strings.TrimPrefix(newID(""), "")[:16]
	command := "SAVEPOINT " + b.savepoint
	if dialect == "sqlserver" {
		command = "SAVE TRANSACTION " + b.savepoint
	}
	if dialect == "db2" {
		command += " ON ROLLBACK RETAIN CURSORS"
	}
	if _, err = c.conn.ExecContext(ctx, command); err != nil {
		return nil, err
	}
	return b, nil
}
func (b *editBoundary) exec(ctx context.Context, q string, args ...any) (sql.Result, error) {
	if b.tx != nil {
		return b.tx.ExecContext(ctx, q, args...)
	}
	return b.conn.ExecContext(ctx, q, args...)
}
func (b *editBoundary) finish(ctx context.Context) error {
	if b.tx != nil {
		return b.tx.Commit()
	}
	if b.dialect == "oracle" || b.dialect == "dameng" || b.dialect == "sqlserver" {
		return nil
	}
	_, err := b.conn.ExecContext(ctx, "RELEASE SAVEPOINT "+b.savepoint)
	return err
}
func (b *editBoundary) rollback(ctx context.Context) error {
	if b.tx != nil {
		return b.tx.Rollback()
	}
	q := "ROLLBACK TO SAVEPOINT " + b.savepoint
	if b.dialect == "oracle" || b.dialect == "dameng" {
		q = "ROLLBACK TO " + b.savepoint
	}
	if b.dialect == "sqlserver" {
		q = "ROLLBACK TRANSACTION " + b.savepoint
	}
	_, err := b.conn.ExecContext(ctx, q)
	if err == nil && b.dialect != "oracle" && b.dialect != "dameng" && b.dialect != "sqlserver" {
		_, err = b.conn.ExecContext(ctx, "RELEASE SAVEPOINT "+b.savepoint)
	}
	return err
}
func invalidateEditConnection(s *session, c *console) {
	c.lost.Store(true)
	_ = c.conn.Raw(func(any) error { return driver.ErrBadConn })
	_ = c.conn.Close()
	s.emit("console.updated", ConsoleView{c.id, c.context.Database, c.context.Schema, "lost"})
}
func editSafeFailure(err error) *APIError {
	var api *APIError
	if errors.As(err, &api) {
		return api
	}
	return editError("edit_database_error", "The database rejected a change. No field values are included in this diagnostic.")
}
