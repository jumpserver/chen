package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

type parityOracleConn struct {
	*editTxConn
	auto          bool
	failSavepoint bool
}

func (c *parityOracleConn) ChenSetAutoCommit(enabled bool) bool {
	old := c.auto
	c.auto = enabled
	return old
}
func (c *parityOracleConn) QueryContext(context.Context, string, []driver.NamedValue) (driver.Rows, error) {
	return &executionRows{columns: []string{"id"}, types: []string{"VARCHAR"}, values: [][]driver.Value{{"outer-transaction"}}}, nil
}
func (c *parityOracleConn) ExecContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Result, error) {
	if c.failSavepoint && strings.HasPrefix(q, "SAVEPOINT ") {
		return nil, errors.New("savepoint failure")
	}
	if c.auto && (strings.HasPrefix(q, "UPDATE ") || strings.HasPrefix(q, "SAVEPOINT ")) {
		c.s.commits++
	}
	return c.editTxConn.ExecContext(ctx, q, args)
}

type parityOracleConnector struct{ c *parityOracleConn }

func (c parityOracleConnector) Connect(context.Context) (driver.Conn, error) { return c.c, nil }
func (c parityOracleConnector) Driver() driver.Driver                        { return editTxDriver{c.c.s} }

func TestParityOracleSavepointDoesNotCommitOuterTransaction(t *testing.T) {
	for _, mode := range []string{"finish", "rollback", "savepoint failure"} {
		t.Run(mode, func(t *testing.T) {
			state := &editTxTestState{state: "active"}
			raw := &parityOracleConn{editTxConn: &editTxConn{state}, auto: true, failSavepoint: mode == "savepoint failure"}
			db := sql.OpenDB(parityOracleConnector{raw})
			defer db.Close()
			conn, err := db.Conn(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			b, err := beginEditBoundary(context.Background(), &console{conn: conn}, "oracle")
			if mode == "savepoint failure" {
				if err == nil || !raw.auto {
					t.Fatal("failure did not restore driver mode")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if raw.auto {
				t.Fatal("implicit commit still enabled")
			}
			if _, err = b.exec(context.Background(), "UPDATE t SET n=1"); err != nil {
				t.Fatal(err)
			}
			if mode == "rollback" {
				err = b.rollback(context.Background())
			} else {
				err = b.finish(context.Background())
			}
			if err != nil || state.commits != 0 || state.rollbacks != 0 || !raw.auto {
				t.Fatalf("outer transaction changed: %+v %v auto=%v", state, err, raw.auto)
			}
		})
	}
}

func TestParityDb2AndClickHouseCopiedSQL(t *testing.T) {
	for _, d := range []string{"db2", "clickhouse"} {
		typeName := "varchar"
		numberType := "bigint"
		if d == "clickhouse" {
			typeName = "Nullable(String)"
			numberType = "UInt64"
		}
		r := &Result{original: [][]any{{"9007199254740993", "a'); DELETE FROM t; -- 中😀"}}}
		x := &editResolved{table: sqlservice.EditTable{Schema: "app", Table: "t", PrimaryKey: []string{"id"}, Columns: []sqlservice.EditColumn{{Name: "id", DataType: numberType, PrimaryKey: true}, {Name: "name", DataType: typeName}}}}
		x.columns = x.table.Columns
		out, err := buildCopiedSQL(d, r, x, copySQLRequest{Operation: "insert", Rows: []int{0}, Columns: []int{0, 1}})
		if err != nil || !strings.Contains(out.SQL, "9007199254740993") {
			t.Fatalf("%s copy: %+v %v", d, out, err)
		}
		a, err := sqlservice.Analyze(sqlservice.Dialect(d), out.SQL)
		if err != nil || len(a.Statements) != 1 || a.Statements[0].Kind != "insert" {
			t.Fatalf("copied data escaped SQL literal: %s %v", out.SQL, err)
		}
	}
	if _, err := copySQLLiteral("db2", "TIMESTAMP", "2026-11-03 12:00:00.123456789123"); err == nil {
		t.Fatal("Db2 timestamp was silently rounded to nanoseconds")
	}
}

func TestParityPostgresTLSPreferencesAreExplicit(t *testing.T) {
	for _, mode := range []string{"require", "verify-full", "prefer", "allow"} {
		c := jumpserver.ConnectionConfig{Dialect: "postgresql", Host: "db.test", Port: 5432, TLS: jumpserver.TLSConfig{Enabled: true, Mode: mode}}
		tls, err := makeTLS(c)
		if err != nil {
			t.Fatal(err)
		}
		cfg, _ := pgx.ParseConfig("sslmode=disable")
		cfg.TLSConfig = tls
		cfg.Fallbacks = nil
		configurePostgresTransport(cfg, c, tls)
		want := mode == "allow"
		if (len(cfg.Fallbacks) == 1) != want {
			t.Fatal("unexpected fallback", mode)
		}
		if mode == "allow" && (cfg.TLSConfig != nil || cfg.Fallbacks[0].TLSConfig == nil) {
			t.Fatal("allow order reversed")
		}
		if mode != "allow" && (cfg.TLSConfig != nil || cfg.DialFunc == nil) {
			t.Fatal("TLS negotiation and cancellation must share a verified dialer")
		}
	}
}

type parityDenyProvider struct{ executionProvider }

func (p *parityDenyProvider) Authorize(_ context.Context, _ *jumpserver.Grant, command string) error {
	if strings.HasPrefix(strings.TrimSpace(command), "DELETE ") {
		return &jumpserver.PolicyError{Code: "command_rejected_by_acl"}
	}
	return nil
}
func TestParityNestedSQLCannotHideFromACL(t *testing.T) {
	state := &executionDriverState{}
	v, s, c, e := executionFixture(t, "sqlserver", "BEGIN DELETE FROM app_table; END", state)
	v.provider = &parityDenyProvider{}
	v.run(s, c, e)
	if e.snapshot().Status != "failed" || len(state.commands) != 0 {
		t.Fatalf("nested DELETE reached database: %+v %v", e.snapshot(), state.commands)
	}
}
