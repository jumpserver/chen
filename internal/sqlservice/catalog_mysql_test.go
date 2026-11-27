package sqlservice

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net"
	"os"
	"slices"
	"testing"

	"github.com/go-sql-driver/mysql"
)

// Load the corresponding testdata/catalog_*.sql into a disposable database.
// Use the fixture's restricted account and the normal MySQL prepared protocol.
func TestMySQLProtocolCatalog(t *testing.T) {
	dsn := os.Getenv("CHEN_CATALOG_TEST_DSN")
	if dsn == "" {
		t.Skip("CHEN_CATALOG_TEST_DSN is unset")
	}
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	conn, err := db.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	var names []string
	after := ""
	for {
		page, err := DatabaseRows(ctx, conn, MySQL, "", after, 1)
		if err != nil {
			t.Fatal(err)
		}
		if len(page) == 0 {
			break
		}
		if len(page) > 2 || page[0].Name <= after {
			t.Fatalf("bad page after %q: %v", after, page)
		}
		after = page[0].Name
		names = append(names, after)
		if len(page) == 1 {
			break
		}
	}
	if !slices.Contains(names, "chen_qa") || !slices.Contains(names, "chen_qb") ||
		!slices.Contains(names, "chen_qa_aa") || !slices.Contains(names, "chen_qa_ab") || slices.Contains(names, "chen_hidden") {
		t.Fatalf("visible databases: %v", names)
	}
	contexts, err := ConsoleContextNames(ctx, conn, MySQL, "", "chen_qa", 1)
	if err != nil || !slices.Equal(contexts, []string{"chen_qa_aa", "chen_qa_ab"}) {
		t.Fatalf("console contexts after chen_qa: %v, %v", contexts, err)
	}
	for _, name := range []string{"CHEN_QA", "chen_hidden"} {
		if err := catalogDatabaseAccess(ctx, conn, MySQL, name); !errors.Is(err, ErrCatalogForbidden) {
			t.Fatalf("database %s: %v", name, err)
		}
	}
	req := CatalogObjectRequest{Database: "chen_qa", Limit: 1}
	items, err := ObjectRows(ctx, conn, MySQL, req)
	if err != nil || len(items) != 2 || items[0].Name != "Alpha" || items[1].Name != "v_report" {
		t.Fatalf("first objects: %v, %v", items, err)
	}
	for _, search := range []struct{ query, kind, want string }{
		{"_", "view", "v_report"}, {"%", "", ""}, {"!", "", ""}, {"Alpha", "table", "Alpha"},
	} {
		page, err := ObjectRows(ctx, conn, MySQL, CatalogObjectRequest{Database: req.Database, Query: search.query, Kind: search.kind})
		if err != nil {
			t.Fatal(err)
		}
		if search.want == "" && len(page) != 0 || search.want != "" && (len(page) != 1 || page[0].Name != search.want) {
			t.Fatalf("object search %q (%s): %v", search.query, search.kind, page)
		}
	}
	req.AfterSchema, req.AfterName = items[0].Schema, items[0].Name
	next, err := ObjectRows(ctx, conn, MySQL, req)
	if err != nil || len(next) != 1 || next[0].Name != "v_report" || next[0].Kind != "view" {
		t.Fatalf("next objects: %v, %v", next, err)
	}
	found, err := MySQLSearchColumns(ctx, conn, req.Database, items, 10)
	if err != nil || len(found) != 2 || len(found["Alpha"]) != 2 || len(found["v_report"]) != 1 {
		t.Fatalf("column search: %v, %v", found, err)
	}
	for _, name := range []string{"alpha", "missing"} {
		_, err := ColumnRows(ctx, conn, MySQL, CatalogColumnRequest{Database: req.Database, Table: name})
		if !errors.Is(err, ErrCatalogNotFound) {
			t.Fatalf("object %s: %v", name, err)
		}
	}
	cols, err := ColumnRows(ctx, conn, MySQL, CatalogColumnRequest{Database: req.Database, Table: "Alpha", AfterOrdinal: 1})
	if err != nil || len(cols) != 1 || cols[0].Name != "label" {
		t.Fatalf("column page: %v, %v", cols, err)
	}
	items, err = ObjectRows(ctx, conn, MySQL, CatalogObjectRequest{Database: "chen_qb"})
	if err != nil || len(items) != 0 {
		t.Fatalf("empty directory: %v, %v", items, err)
	}
}

// Reject every catalog query to verify error identity and absence of a retry.
type failingCatalogConn struct {
	driver.Conn
	err   error
	calls int
}

func (c *failingCatalogConn) Close() error { return nil }
func (c *failingCatalogConn) QueryContext(context.Context, string, []driver.NamedValue) (driver.Rows, error) {
	c.calls++
	return nil, c.err
}

type failingCatalogConnector struct{ conn *failingCatalogConn }

func (c failingCatalogConnector) Connect(context.Context) (driver.Conn, error) { return c.conn, nil }
func (c failingCatalogConnector) Driver() driver.Driver                        { return nil }

func TestCatalogQueryErrors(t *testing.T) {
	for _, cause := range []error{
		&mysql.MySQLError{Number: 1142, Message: "SELECT command denied"},
		&net.OpError{Op: "read", Net: "tcp", Err: io.ErrClosedPipe},
		context.DeadlineExceeded, io.ErrUnexpectedEOF,
		&mysql.MySQLError{Number: 1064, Message: "unexpected syntax error"},
	} {
		t.Run(cause.Error(), func(t *testing.T) {
			raw := &failingCatalogConn{err: cause}
			db := sql.OpenDB(failingCatalogConnector{raw})
			defer db.Close()
			ctx := context.Background()
			conn, err := db.Conn(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			items, err := DatabaseRows(ctx, conn, MySQL, "", "", 1)
			if len(items) != 0 || !errors.Is(err, cause) || raw.calls != 1 {
				t.Fatalf("items=%v error=%v queries=%d", items, err, raw.calls)
			}
		})
	}
}
