package sqlservice

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"strings"
	"testing"
)

type recordingConnector struct {
	query    string
	args     []driver.NamedValue
	deadline bool
	values   [][]driver.Value
}

func (r *recordingConnector) Connect(context.Context) (driver.Conn, error) {
	return recordingConn{r}, nil
}
func (r *recordingConnector) Driver() driver.Driver { return recordingDriver{r} }

type recordingDriver struct{ r *recordingConnector }

func (d recordingDriver) Open(string) (driver.Conn, error) { return recordingConn{d.r}, nil }

type recordingConn struct{ r *recordingConnector }

func (recordingConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("unexpected prepare")
}
func (recordingConn) Close() error              { return nil }
func (recordingConn) Begin() (driver.Tx, error) { return nil, errors.New("unexpected transaction") }
func (c recordingConn) QueryContext(ctx context.Context, q string, args []driver.NamedValue) (driver.Rows, error) {
	c.r.query = q
	c.r.args = args
	_, c.r.deadline = ctx.Deadline()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return &recordingRows{values: c.r.values}, nil
}

type recordingRows struct {
	values [][]driver.Value
	i      int
}

func (r *recordingRows) Columns() []string {
	if len(r.values) > 0 {
		return make([]string, len(r.values[0]))
	}
	return []string{"a", "b", "c", "d", "e"}
}
func (*recordingRows) Close() error { return nil }
func (r *recordingRows) Next(dest []driver.Value) error {
	if r.i >= len(r.values) {
		return io.EOF
	}
	copy(dest, r.values[r.i])
	r.i++
	return nil
}
func catalogConnection(t *testing.T, r *recordingConnector) *sql.Conn {
	t.Helper()
	db := sql.OpenDB(r)
	t.Cleanup(func() { db.Close() })
	conn, e := db.Conn(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func TestMetadataParameterizedAndBounded(t *testing.T) {
	for _, d := range []Dialect{MySQL, PostgreSQL} {
		t.Run(string(d), func(t *testing.T) {
			r := &recordingConnector{values: [][]driver.Value{{"db", "public", "users", "BASE TABLE", "comment"}}}
			conn := catalogConnection(t, r)
			schema := `public'; DROP TABLE users; --`
			got, e := Metadata(context.Background(), conn, d, MetadataRequest{Context: Context{Schema: schema}, Prefix: "user_%", Limit: 10000})
			if e != nil {
				t.Fatal(e)
			}
			if len(got) != 1 || got[0].Name != "users" {
				t.Fatalf("result %+v", got)
			}
			if strings.Contains(r.query, schema) || strings.Contains(r.query, "user_%") {
				t.Fatal("interpolated catalog values")
			}
			if d == PostgreSQL {
				if !strings.Contains(r.query, "pg_table_is_visible(c.oid)") {
					t.Fatal("unqualified table suggestions must resolve live search_path visibility")
				}
				if !strings.Contains(r.query, "n.nspname = ANY(current_schemas(false))") {
					t.Fatal("implicit pg_catalog must not crowd out user-schema suggestions")
				}
			}
			if !r.deadline {
				t.Fatal("catalog query has no timeout")
			}
			if r.args[len(r.args)-1].Value != int64(MaxCompletionItems) {
				t.Fatalf("unbounded limit: %+v", r.args)
			}
			found := false
			for _, a := range r.args {
				if a.Value == "user!_!%%" {
					found = true
				}
			}
			if !found {
				t.Fatalf("wildcards not escaped: %+v", r.args)
			}
		})
	}
}
func TestColumnsParameterized(t *testing.T) {
	for _, d := range []Dialect{MySQL, PostgreSQL} {
		t.Run(string(d), func(t *testing.T) {
			r := &recordingConnector{values: [][]driver.Value{{"field", "bigint", false, "comment"}}}
			conn := catalogConnection(t, r)
			table := `users";DROP TABLE t;--`
			got, e := Columns(context.Background(), conn, d, ColumnRequest{Table: table, Schema: "public"})
			if e != nil {
				t.Fatal(e)
			}
			if len(got) != 1 || got[0].Name != "field" || got[0].Nullable {
				t.Fatalf("result %+v", got)
			}
			if strings.Contains(r.query, table) {
				t.Fatal("interpolated table")
			}
			found := false
			for _, a := range r.args {
				if a.Value == table {
					found = true
				}
			}
			if !found {
				t.Fatal("missing table parameter")
			}
		})
	}
}
func TestCatalogCancellation(t *testing.T) {
	r := &recordingConnector{}
	conn := catalogConnection(t, r)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := Metadata(ctx, conn, PostgreSQL, MetadataRequest{}); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel ignored: %v", err)
	}
}
