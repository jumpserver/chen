package sqlservice

import (
	"errors"
	"strings"

	pg "github.com/pganalyze/pg_query_go/v6"
	"github.com/pingcap/tidb/pkg/parser"
	"github.com/pingcap/tidb/pkg/parser/ast"
	"github.com/pingcap/tidb/pkg/parser/format"
	"github.com/pingcap/tidb/pkg/parser/mysql"
	"github.com/pingcap/tidb/pkg/parser/opcode"
	"google.golang.org/protobuf/proto"
)

// ValidateBrowseWhere proves a bounded expression grammar, not just a SELECT
// classification. Functions, subqueries, parameters and statement tails cannot
// enter this path. Server semantic/type checks still run on the same connection.
func ValidateBrowseWhere(d Dialect, where string, opts AnalyzeOptions, columns []EditColumn) error {
	if strings.TrimSpace(where) == "" {
		return nil
	}
	bad := errors.New("WHERE supports column comparisons, AND/OR/NOT, NULL, LIKE, IN and BETWEEN; functions, subqueries and statement clauses are unavailable")
	if len(where) > 16384 || strings.ContainsRune(where, 0) {
		return bad
	}
	known := func(name string) bool {
		for _, c := range columns {
			if editNameKey(name, d) == editNameKey(c.Name, d) {
				return true
			}
		}
		return false
	}
	document := "SELECT 1 WHERE (\n" + where + "\n)"
	if d == PostgreSQL {
		tree, err := pg.Parse(document)
		if err != nil {
			return err
		}
		if len(tree.Stmts) != 1 {
			return bad
		}
		stmt := tree.Stmts[0].Stmt.GetSelectStmt()
		if stmt == nil || stmt.WhereClause == nil {
			return bad
		}
		expr := stmt.WhereClause
		stmt.WhereClause = nil
		base, _ := pg.Parse("SELECT 1")
		if !proto.Equal(stmt, base.Stmts[0].Stmt.GetSelectStmt()) {
			return bad
		}
		var visit func(*pg.Node, int) bool
		visit = func(n *pg.Node, depth int) bool {
			if n == nil || depth > 32 {
				return false
			}
			all := func(ns []*pg.Node) bool {
				if len(ns) > 100 {
					return false
				}
				for _, n := range ns {
					if !visit(n, depth+1) {
						return false
					}
				}
				return true
			}
			switch {
			case n.GetColumnRef() != nil:
				fields := n.GetColumnRef().Fields
				return len(fields) == 1 && fields[0].GetString_() != nil && known(fields[0].GetString_().Sval)
			case n.GetAConst() != nil:
				return n.GetAConst().GetBsval() == nil
			case n.GetBoolExpr() != nil:
				return all(n.GetBoolExpr().Args)
			case n.GetNullTest() != nil:
				return visit(n.GetNullTest().Arg, depth+1)
			case n.GetBooleanTest() != nil:
				return visit(n.GetBooleanTest().Arg, depth+1)
			case n.GetList() != nil:
				return all(n.GetList().Items)
			case n.GetAExpr() != nil:
				a := n.GetAExpr()
				switch a.Kind {
				case pg.A_Expr_Kind_AEXPR_OP, pg.A_Expr_Kind_AEXPR_IN, pg.A_Expr_Kind_AEXPR_LIKE, pg.A_Expr_Kind_AEXPR_ILIKE, pg.A_Expr_Kind_AEXPR_BETWEEN, pg.A_Expr_Kind_AEXPR_NOT_BETWEEN:
				default:
					return false
				}
				if len(a.Name) != 1 || a.Name[0].GetString_() == nil {
					return false
				}
				switch a.Name[0].GetString_().Sval {
				case "=", "<>", "!=", "<", ">", "<=", ">=", "~~", "!~~", "~~*", "!~~*", "BETWEEN", "NOT BETWEEN":
				default:
					return false
				}
				return visit(a.Lexpr, depth+1) && visit(a.Rexpr, depth+1)
			}
			return false
		}
		if !visit(expr, 0) {
			return bad
		}
		return nil
	}
	if d != MySQL {
		return ErrUnsupportedDialect
	}
	input, err := mysqlParserInput(document, opts)
	if err != nil {
		return err
	}
	p := parser.New()
	mode, err := mysql.GetSQLMode(opts.MySQLSQLMode)
	if err != nil {
		return err
	}
	p.SetSQLMode(mode)
	nodes, _, err := p.ParseSQL(input)
	if err != nil {
		return err
	}
	if len(nodes) != 1 {
		return bad
	}
	stmt, ok := nodes[0].(*ast.SelectStmt)
	if !ok || stmt.Where == nil {
		return bad
	}
	expr := stmt.Where
	stmt.Where = nil
	var restored strings.Builder
	if stmt.Restore(format.NewRestoreCtx(format.DefaultRestoreFlags, &restored)) != nil || restored.String() != "SELECT 1" {
		return bad
	}
	v := &browseWhereVisitor{known: known}
	_, ok = expr.Accept(v)
	if !ok || v.invalid {
		return bad
	}
	return nil
}

type browseWhereVisitor struct {
	known   func(string) bool
	invalid bool
	depth   int
}

func (v *browseWhereVisitor) Enter(n ast.Node) (ast.Node, bool) {
	v.depth++
	if v.depth > 32 {
		v.invalid = true
	}
	switch e := n.(type) {
	case *ast.ColumnNameExpr:
		if e.Name.Schema.O != "" || e.Name.Table.O != "" || !v.known(e.Name.Name.O) {
			v.invalid = true
		}
		return n, true
	case ast.ParamMarkerExpr:
		v.invalid = true
		return n, true
	case ast.ValueExpr:
		return n, true
	case *ast.ParenthesesExpr, *ast.IsNullExpr, *ast.IsTruthExpr, *ast.BetweenExpr, *ast.PatternLikeOrIlikeExpr:
	case *ast.PatternInExpr:
		if e.Sel != nil || len(e.List) > 100 {
			v.invalid = true
		}
	case *ast.BinaryOperationExpr:
		switch e.Op {
		case opcode.LogicAnd, opcode.LogicOr, opcode.EQ, opcode.NE, opcode.LT, opcode.LE, opcode.GT, opcode.GE, opcode.NullEQ:
		default:
			v.invalid = true
		}
	case *ast.UnaryOperationExpr:
		switch e.Op {
		case opcode.Not, opcode.Not2:
		case opcode.Plus, opcode.Minus:
			if _, ok := e.V.(ast.ValueExpr); !ok {
				v.invalid = true
			}
		default:
			v.invalid = true
		}
	default:
		v.invalid = true
	}
	return n, v.invalid
}
func (v *browseWhereVisitor) Leave(n ast.Node) (ast.Node, bool) { v.depth--; return n, !v.invalid }
