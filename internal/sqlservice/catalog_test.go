package sqlservice

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"
)

type catalogStep struct {
	rows    [][]driver.Value
	columns int
	err     error
}
type catalogCall struct {
	sql      string
	args     []driver.NamedValue
	deadline time.Time
}
type catalogScript struct {
	steps []catalogStep
	calls []catalogCall
}

func (s *catalogScript) Connect(context.Context) (driver.Conn, error) {
	return catalogScriptConn{s}, nil
}
func (s *catalogScript) Driver() driver.Driver { return catalogScriptDriver{s} }

type catalogScriptDriver struct{ s *catalogScript }

func (d catalogScriptDriver) Open(string) (driver.Conn, error) { return catalogScriptConn{d.s}, nil }

type catalogScriptConn struct{ s *catalogScript }

func (c catalogScriptConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("unexpected prepare")
}
func (c catalogScriptConn) Close() error { return nil }
func (c catalogScriptConn) Begin() (driver.Tx, error) {
	return nil, errors.New("unexpected transaction")
}
func (c catalogScriptConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	deadline, _ := ctx.Deadline()
	c.s.calls = append(c.s.calls, catalogCall{query, append([]driver.NamedValue{}, args...), deadline})
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if len(c.s.steps) == 0 {
		return nil, errors.New("unexpected catalog query")
	}
	step := c.s.steps[0]
	c.s.steps = c.s.steps[1:]
	if step.err != nil {
		return nil, step.err
	}
	return &catalogScriptRows{rows: step.rows, columns: step.columns}, nil
}

type catalogScriptRows struct {
	rows              [][]driver.Value
	columns, position int
}

func (r *catalogScriptRows) Columns() []string {
	if r.columns == 0 && len(r.rows) > 0 {
		r.columns = len(r.rows[0])
	}
	return make([]string, r.columns)
}
func (r *catalogScriptRows) Close() error { return nil }
func (r *catalogScriptRows) Next(dest []driver.Value) error {
	if r.position >= len(r.rows) {
		return io.EOF
	}
	copy(dest, r.rows[r.position])
	r.position++
	return nil
}
func scriptedCatalogConn(t *testing.T, steps ...catalogStep) (*sql.Conn, *catalogScript) {
	t.Helper()
	script := &catalogScript{steps: steps}
	db := sql.OpenDB(script)
	t.Cleanup(func() { db.Close() })
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn, script
}
func catalogBool(value bool) catalogStep { return catalogStep{rows: [][]driver.Value{{value}}} }
func assertCatalogParameters(t *testing.T, call catalogCall, unsafe ...string) {
	t.Helper()
	if call.deadline.IsZero() || time.Until(call.deadline) > catalogTimeout+time.Second {
		t.Fatal("catalog query did not inherit bounded deadline")
	}
	for _, value := range unsafe {
		if strings.Contains(call.sql, value) {
			t.Fatalf("catalog value interpolated into SQL: %q", value)
		}
	}
}
func hasCatalogArgument(call catalogCall, value any) bool {
	for _, arg := range call.args {
		if arg.Value == value {
			return true
		}
	}
	return false
}

func TestCatalogSchemaKeyset(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		t.Run(string(dialect), func(t *testing.T) {
			database := "db' name"
			after := "after' 中文"
			conn, script := scriptedCatalogConn(t, catalogBool(true), catalogStep{rows: [][]driver.Value{{database, "schema"}, {database, "中文"}}})
			got, err := SchemaRows(context.Background(), conn, dialect, database, after, 1)
			if err != nil {
				t.Fatal(err)
			}
			if len(got) != 2 {
				t.Fatal("schema limit+1 was trimmed before the HTTP cursor layer")
			}
			if len(script.calls) != 2 {
				t.Fatal("database authorization was not checked")
			}
			call := script.calls[1]
			assertCatalogParameters(t, call, database, after)
			if !hasCatalogArgument(call, database) || !hasCatalogArgument(call, after) || !hasCatalogArgument(call, int64(2)) {
				t.Fatalf("keyset parameters missing: %+v", call.args)
			}
			if dialect == MySQL && !strings.Contains(call.sql, "ORDER BY BINARY SCHEMA_NAME") {
				t.Fatal("MySQL schema ordering is not binary")
			}
			if dialect == PostgreSQL && (!strings.Contains(call.sql, `COLLATE pg_catalog."C"`) || !strings.Contains(call.sql, "has_schema_privilege") || !strings.Contains(call.sql, "pg!_%")) {
				t.Fatal("PostgreSQL schema scope or deterministic order missing")
			}
		})
	}
}
func TestCatalogObjectSearchAndKeyset(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		t.Run(string(dialect), func(t *testing.T) {
			database := "authorized-db"
			query := "50%_!'; --"
			afterName := `Name"cursor`
			afterSchema := "schema'cursor"
			conn, script := scriptedCatalogConn(t, catalogBool(true), catalogStep{rows: [][]driver.Value{{database, "public", "a", "view", "materialized_view", "comment"}, {database, "public", "b", "view", "view", ""}}})
			got, err := ObjectRows(context.Background(), conn, dialect, CatalogObjectRequest{Database: database, Kind: "view", Query: query, AfterSchema: afterSchema, AfterName: afterName, Limit: 1})
			if err != nil {
				t.Fatal(err)
			}
			if len(got) != 2 || got[0].Kind != "view" || got[0].ObjectType != "materialized_view" {
				t.Fatalf("object subtype or extra cursor row lost: %+v", got)
			}
			call := script.calls[1]
			assertCatalogParameters(t, call, database, query, afterName, afterSchema)
			for _, value := range []any{database, "view", "%50!%!_!!'; --%", afterSchema, afterName, int64(2)} {
				if !hasCatalogArgument(call, value) {
					t.Fatalf("object query missing parameter %v", value)
				}
			}
			if dialect == PostgreSQL && (!strings.Contains(call.sql, "has_any_column_privilege") || !strings.Contains(call.sql, "has_table_privilege") || !strings.Contains(call.sql, `COLLATE pg_catalog."C"`)) {
				t.Fatal("PostgreSQL object visibility or keyset order missing")
			}
			if dialect == MySQL && (!strings.Contains(call.sql, "BINARY TABLE_SCHEMA = BINARY ?") || !strings.Contains(call.sql, "ORDER BY BINARY TABLE_SCHEMA, BINARY TABLE_NAME")) {
				t.Fatal("MySQL scope or keyset can change under connection collation")
			}
		})
	}
}
func TestCatalogColumnPermissionsAndOrdinal(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		t.Run(string(dialect), func(t *testing.T) {
			database, schema, table := "authorized-db", "public", `table"quoted`
			access := catalogStep{rows: [][]driver.Value{{true, true, true}}}
			if dialect == MySQL {
				schema = database
				access = catalogBool(true)
			}
			conn, script := scriptedCatalogConn(t, catalogBool(true), access, catalogStep{rows: [][]driver.Value{{database, schema, table, "col_a", "bigint", false, "primary identifier", int64(3)}, {database, schema, table, "col_b", "text", true, "", int64(4)}}})
			got, err := ColumnRows(context.Background(), conn, dialect, CatalogColumnRequest{Database: database, Schema: schema, Table: table, AfterOrdinal: 2, Limit: 1})
			if err != nil {
				t.Fatal(err)
			}
			if len(got) != 2 || got[0].Ordinal != 3 || got[0].Nullable {
				t.Fatalf("column types or ordinal lost: %+v", got)
			}
			encoded, _ := json.Marshal(got[0])
			if strings.Contains(string(encoded), "ordinal") {
				t.Fatal("internal cursor ordinal leaked into the public column shape")
			}
			call := script.calls[2]
			assertCatalogParameters(t, call, table, database)
			for _, value := range []any{table, schema, int64(2)} {
				if !hasCatalogArgument(call, value) {
					t.Fatal("column query did not parameterize identifier or page ordinal")
				}
			}
			if dialect == PostgreSQL && !strings.Contains(call.sql, "has_column_privilege(c.oid, a.attnum") {
				t.Fatal("column-level grants are not enforced")
			}
		})
	}
}
func TestCatalogAccessErrors(t *testing.T) {
	for _, dialect := range []Dialect{MySQL, PostgreSQL} {
		t.Run("database/"+string(dialect), func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogBool(false))
			if _, err := ObjectRows(context.Background(), conn, dialect, CatalogObjectRequest{Database: "denied"}); !errors.Is(err, ErrCatalogForbidden) {
				t.Fatalf("scope mismatch not forbidden: %v", err)
			}
			if len(script.calls) != 1 {
				t.Fatal("catalog objects queried after authorization failed")
			}
		})
	}
	for _, test := range []struct {
		name string
		step catalogStep
		want error
	}{
		{"no-schema-usage", catalogBool(false), ErrCatalogForbidden},
		{"missing-schema", catalogStep{columns: 1}, ErrCatalogNotFound},
	} {
		t.Run(test.name, func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogBool(true), test.step)
			_, err := ObjectRows(context.Background(), conn, PostgreSQL, CatalogObjectRequest{Database: "db", Schema: "private"})
			if !errors.Is(err, test.want) {
				t.Fatalf("error %v want %v", err, test.want)
			}
			if len(script.calls) != 2 {
				t.Fatal("objects queried after schema rejection")
			}
		})
	}
	for _, test := range []struct {
		name string
		step catalogStep
		want error
	}{
		{"schema-denied", catalogStep{rows: [][]driver.Value{{false, true, true}}}, ErrCatalogForbidden},
		{"table-denied", catalogStep{rows: [][]driver.Value{{true, false, false}}}, ErrCatalogForbidden},
		{"no-column-grants", catalogStep{rows: [][]driver.Value{{true, true, false}}}, ErrCatalogForbidden},
		{"missing-table", catalogStep{columns: 3}, ErrCatalogNotFound},
	} {
		t.Run(test.name, func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogBool(true), test.step)
			_, err := ColumnRows(context.Background(), conn, PostgreSQL, CatalogColumnRequest{Database: "db", Schema: "private", Table: "items"})
			if !errors.Is(err, test.want) {
				t.Fatalf("error %v want %v", err, test.want)
			}
			if len(script.calls) != 2 {
				t.Fatal("columns queried after permission rejection")
			}
		})
	}
	t.Run("mysql-hidden-object", func(t *testing.T) {
		conn, script := scriptedCatalogConn(t, catalogBool(true), catalogBool(false))
		_, err := ColumnRows(context.Background(), conn, MySQL, CatalogColumnRequest{Database: "db", Table: "hidden"})
		if !errors.Is(err, ErrCatalogNotFound) || len(script.calls) != 2 {
			t.Fatal("MySQL hidden object must not be probed through user SQL")
		}
	})
}
func TestCatalogRejectsScopeAndInputBeforeQueries(t *testing.T) {
	conn, script := scriptedCatalogConn(t)
	for _, run := range []func() error{
		func() error { _, e := SchemaRows(context.Background(), conn, PostgreSQL, "", "", 10); return e },
		func() error {
			_, e := ObjectRows(context.Background(), conn, MySQL, CatalogObjectRequest{Database: "allowed", Schema: "other"})
			return e
		},
		func() error {
			_, e := ColumnRows(context.Background(), conn, MySQL, CatalogColumnRequest{Database: "allowed", Schema: "other", Table: "t"})
			return e
		},
		func() error {
			_, e := ObjectRows(context.Background(), conn, PostgreSQL, CatalogObjectRequest{Database: "db", Schema: "pg_catalog"})
			return e
		},
		func() error {
			_, e := ColumnRows(context.Background(), conn, PostgreSQL, CatalogColumnRequest{Database: "db", Schema: "information_schema", Table: "tables"})
			return e
		},
	} {
		if err := run(); !errors.Is(err, ErrCatalogForbidden) {
			t.Fatalf("scope accepted: %v", err)
		}
	}
	for _, req := range []CatalogObjectRequest{{Database: "db", Kind: "sequence"}, {Database: "db", Query: strings.Repeat("a", 257)}, {Database: "db", AfterName: "bad\x00name"}} {
		if _, err := ObjectRows(context.Background(), conn, PostgreSQL, req); err == nil {
			t.Fatal("invalid catalog input accepted")
		}
	}
	if _, err := ColumnRows(context.Background(), conn, PostgreSQL, CatalogColumnRequest{Database: "db", Table: "table"}); err == nil {
		t.Fatal("PostgreSQL schema guessed from search_path")
	}
	if len(script.calls) != 0 {
		t.Fatal("invalid scope caused a database query")
	}
}
func TestCatalogDeadlineCancellationAndLimit(t *testing.T) {
	conn, _ := scriptedCatalogConn(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := SchemaRows(ctx, conn, PostgreSQL, "db", "", 10); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation lost: %v", err)
	}
	for _, limit := range []int{-1, 0, MaxCatalogPageSize + 1} {
		t.Run(fmt.Sprint(limit), func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogBool(true), catalogStep{columns: 2})
			_, err := SchemaRows(context.Background(), conn, MySQL, "db", "", limit)
			if err != nil {
				t.Fatal(err)
			}
			if !hasCatalogArgument(script.calls[1], int64(MaxCatalogPageSize+1)) {
				t.Fatal("catalog result page is not bounded")
			}
		})
	}
}
