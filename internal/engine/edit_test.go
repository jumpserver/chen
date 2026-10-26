package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

func editString(s string) *string { return &s }
func editPlanFixture() (*Result, *editResolved) {
	columns := []sqlservice.EditColumn{{Name: "id", DataType: "BIGINT", PrimaryKey: true, Supported: true}, {Name: "title", DataType: "VARCHAR", Nullable: true, Supported: true}, {Name: "secret", DataType: "VARCHAR", Nullable: true, Supported: true}, {Name: "created", DataType: "TIMESTAMP", Generated: true, HasDefault: true, Supported: true}}
	r := &Result{ID: "res_grid", Rows: [][]any{{"[MASKED]", "old", "[MASKED]", "today"}}, original: [][]any{{"9007199254740993", "old", "protected-original", "today"}}, masked: [][]bool{{true, false, true, false}}, rowRefs: []string{"row_secret_1"}}
	x := &editResolved{table: sqlservice.EditTable{Schema: "public", Table: "items", Columns: columns, PrimaryKey: []string{"id"}}, columns: columns, pk: 0, view: editMetadataView{Supported: true, Mode: "table", Columns: []editColumnView{{Index: 0, Name: "id", PrimaryKey: true, Insertable: true}, {Index: 1, Name: "title", Editable: true, Insertable: true, Nullable: true}, {Index: 2, Name: "secret", Nullable: true}, {Index: 3, Name: "created", Generated: true}}}}
	return r, x
}
func TestEditPlanParametersAndMaskedPredicates(t *testing.T) {
	r, x := editPlanFixture()
	payload := "'; DELETE FROM users; --中文🙂"
	p, err := buildEditPlan("postgresql", "con", r, x, editPreviewRequest{Changes: []editChange{{Op: "update", RowRef: r.rowRefs[0], Values: map[string]*string{"1": &payload}}}})
	if err != nil {
		t.Fatal(err)
	}
	c := p.commands[0]
	if strings.Contains(c.sql, payload) || len(c.args) != 3 || c.args[0] != payload || c.args[1] != "9007199254740993" || c.args[2] != "old" {
		t.Fatalf("parameterization lost: %s %+v", c.sql, c.args)
	}
	if strings.Contains(p.previewSQL, "9007199254740993") || strings.Contains(p.previewSQL, "protected-original") || !strings.Contains(p.previewSQL, "[MASKED]") {
		t.Fatal("preview exposed protected primary key")
	}
	if !strings.Contains(c.policySQL, "9007199254740993") {
		t.Fatal("policy no longer evaluates exact generated write")
	}
	if !strings.Contains(c.sql, `"title" = $3`) {
		t.Fatal("optimistic original-value predicate missing")
	}
}
func TestEditPlanRejectsInvalidChanges(t *testing.T) {
	for _, tc := range []struct {
		name   string
		change editChange
		query  bool
	}{{"forged row", editChange{Op: "delete", RowRef: "forged"}, false}, {"change PK", editChange{Op: "update", RowRef: "row_secret_1", Values: map[string]*string{"0": editString("2")}}, false}, {"masked column", editChange{Op: "update", RowRef: "row_secret_1", Values: map[string]*string{"2": editString("x")}}, false}, {"generated column", editChange{Op: "insert", Values: map[string]*string{"0": editString("2"), "3": editString("yesterday")}}, false}, {"forged index", editChange{Op: "update", RowRef: "row_secret_1", Values: map[string]*string{"99": editString("x")}}, false}, {"query delete", editChange{Op: "delete", RowRef: "row_secret_1"}, true}, {"required PK omitted", editChange{Op: "insert", Values: map[string]*string{"1": editString("x")}}, false}, {"bad bigint", editChange{Op: "insert", Values: map[string]*string{"0": editString("1;DROP")}}, false}} {
		t.Run(tc.name, func(t *testing.T) {
			r, x := editPlanFixture()
			if tc.query {
				x.view.Mode = "query"
			}
			if _, err := buildEditPlan("postgresql", "c", r, x, editPreviewRequest{Changes: []editChange{tc.change}}); err == nil {
				t.Fatal("unsafe change accepted")
			}
		})
	}
}
func TestEditPlanLimitNullDefaultAndCSVSafety(t *testing.T) {
	r, x := editPlanFixture()
	p, err := buildEditPlan("postgresql", "c", r, x, editPreviewRequest{Source: "csv", Changes: []editChange{{Op: "insert", Values: map[string]*string{"0": editString("9007199254740994"), "1": nil}}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(p.commands[0].args) != 2 || p.commands[0].args[1] != nil || strings.Contains(p.commands[0].sql, "created") {
		t.Fatal("NULL/default or omitted generated field changed")
	}
	tooMany := make([]editChange, 501)
	if _, err = buildEditPlan("postgresql", "c", r, x, editPreviewRequest{Changes: tooMany}); err == nil {
		t.Fatal("batch limit missing")
	}
	if _, err = buildEditPlan("postgresql", "c", r, x, editPreviewRequest{Source: "csv", Changes: []editChange{{Op: "delete", RowRef: r.rowRefs[0]}}}); err == nil {
		t.Fatal("CSV deletion accepted")
	}
	if _, err = convertEditValue(sqlservice.EditColumn{DataType: "JSON", Nullable: true}, editString("{bad}")); err == nil {
		t.Fatal("invalid JSON accepted")
	}
	if _, err = convertEditValue(sqlservice.EditColumn{DataType: "VARCHAR", Nullable: false}, nil); err == nil {
		t.Fatal("nullability ignored")
	}
}

type editTxTestState struct {
	state                  string
	commands               []string
	commits, rollbacks     int
	commitErr, rollbackErr error
}
type editTxConnector struct{ s *editTxTestState }

func (c editTxConnector) Connect(context.Context) (driver.Conn, error) { return &editTxConn{c.s}, nil }
func (c editTxConnector) Driver() driver.Driver                        { return editTxDriver{c.s} }

type editTxDriver struct{ s *editTxTestState }

func (d editTxDriver) Open(string) (driver.Conn, error) { return &editTxConn{d.s}, nil }

type editTxConn struct{ s *editTxTestState }

func (c *editTxConn) Prepare(string) (driver.Stmt, error) { return nil, errors.New("unused") }
func (c *editTxConn) Close() error                        { return nil }
func (c *editTxConn) Begin() (driver.Tx, error)           { return &editTxDriverTx{c.s}, nil }
func (c *editTxConn) ChenTransactionState() string        { return c.s.state }
func (c *editTxConn) ExecContext(_ context.Context, q string, _ []driver.NamedValue) (driver.Result, error) {
	c.s.commands = append(c.s.commands, q)
	return driver.RowsAffected(1), nil
}

type editTxDriverTx struct{ s *editTxTestState }

func (tx *editTxDriverTx) Commit() error   { tx.s.commits++; return tx.s.commitErr }
func (tx *editTxDriverTx) Rollback() error { tx.s.rollbacks++; return tx.s.rollbackErr }
func TestEditTransactionBoundariesPreserveUserOwnership(t *testing.T) {
	for _, state := range []string{"idle", "active", "failed", "unknown"} {
		t.Run(state, func(t *testing.T) {
			s := &editTxTestState{state: state}
			db := sql.OpenDB(editTxConnector{s})
			defer db.Close()
			conn, _ := db.Conn(context.Background())
			defer conn.Close()
			b, err := beginEditBoundary(context.Background(), &console{conn: conn}, "postgresql")
			if state == "failed" || state == "unknown" {
				if err == nil {
					t.Fatal("unknown transaction accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if err = b.finish(context.Background()); err != nil {
				t.Fatal(err)
			}
			if state == "idle" {
				if s.commits != 1 {
					t.Fatal("service transaction not committed")
				}
			} else if s.commits != 0 || len(s.commands) != 2 || !strings.HasPrefix(s.commands[0], "SAVEPOINT ") || !strings.HasPrefix(s.commands[1], "RELEASE SAVEPOINT ") {
				t.Fatal("user transaction was committed or not isolated")
			}
		})
	}
}
func TestEditRollbackUsesOnlyOwnSavepoint(t *testing.T) {
	s := &editTxTestState{state: "active"}
	db := sql.OpenDB(editTxConnector{s})
	defer db.Close()
	conn, _ := db.Conn(context.Background())
	defer conn.Close()
	b, err := beginEditBoundary(context.Background(), &console{conn: conn}, "postgresql")
	if err != nil {
		t.Fatal(err)
	}
	if err = b.rollback(context.Background()); err != nil {
		t.Fatal(err)
	}
	if s.rollbacks != 0 || len(s.commands) != 3 || !strings.HasPrefix(s.commands[1], "ROLLBACK TO SAVEPOINT ") {
		t.Fatal("outer transaction rolled back")
	}
}

type editSourceMaskProvider struct{ executionProvider }

func (p *editSourceMaskProvider) Mask(_ *jumpserver.Grant, source, label string, value any) (any, bool) {
	if source == "" || source == "id" || source == "secret" {
		return "[MASKED]", true
	}
	return value, false
}
func TestEditPreciseSourceMaskPreservesProtectedAliasAndMemory(t *testing.T) {
	v := New(&editSourceMaskProvider{}, Options{StateDir: t.TempDir(), MaxResultBytes: 4096})
	defer v.Close()
	s := seedEngineSession(v, "source-mask")
	r := &Result{Columns: []Column{{"key_alias", "BIGINT"}, {"title_alias", "VARCHAR"}, {"unprotected_alias", "VARCHAR"}}, Rows: [][]any{{"[MASKED]", "[MASKED]", "[MASKED]"}}, original: [][]any{{"9007199254740993", "visible", "protected value"}}, masked: [][]bool{{true, true, true}}, rowRefs: []string{"opaque"}, memoryBytes: 150}
	s.resultBytes = 150
	v.applyEditSourceMask(s, r, []sqlservice.EditColumn{{Name: "id"}, {Name: "title"}, {Name: "secret"}})
	if r.Rows[0][0] != "[MASKED]" || r.Rows[0][1] != "visible" || r.Rows[0][2] != "[MASKED]" || r.masked[0][1] {
		t.Fatal("trusted source masking leaked an alias or hid a public column")
	}
	if s.resultBytes != r.memoryBytes || s.resultBytes <= 0 || s.resultBytes > 150 {
		t.Fatal("raw-value retention was not accounted or released")
	}
	v.opts.MaxResultBytes = 1
	v.applyEditSourceMask(s, r, []sqlservice.EditColumn{{Name: "id"}, {Name: "title"}, {Name: "secret"}})
	if len(r.Rows) != 0 || !r.Truncated || s.resultBytes != 0 {
		t.Fatal("resolved masks exceeded memory budget")
	}
}
