package sqlservice

import (
	"errors"
	"fmt"
	"strings"

	pg "github.com/pganalyze/pg_query_go/v6"
	"github.com/pingcap/tidb/pkg/parser"
	"github.com/pingcap/tidb/pkg/parser/ast"
	"github.com/pingcap/tidb/pkg/parser/mysql"
	"google.golang.org/protobuf/reflect/protoreflect"
)

// QueryPageSQL keeps the user's complete query as an inner query expression.
// It never removes an ORDER BY, DISTINCT, set operation or original page window.
// Admission is deliberately narrower than ordinary execution, which is unchanged.
type QueryPageRelation struct{ Schema, Name string }

type QueryPageSQL struct {
	Relations []QueryPageRelation
	CTEs      map[string]bool
	Functions []string
	Operators []string
	Types     []string
	Original  string
	Ordered   bool
	Dialect   Dialect
}

func AnalyzeQueryPage(d Dialect, document string, options AnalyzeOptions) (*QueryPageSQL, error) {
	bad := errors.New("Database paging requires one repeatable SELECT; writes, locks, variables, parameters, executable hints and unverified functions are unsupported")
	if d != PostgreSQL && d != MySQL {
		return nil, errors.New("Database query paging supports PostgreSQL and MySQL only")
	}
	if d == MySQL && options.MySQLVersion < 80031 {
		return nil, errors.New("Database query paging requires MySQL 8.0.31 or later for nested query windows")
	}
	a, err := AnalyzeWithOptions(d, document, options)
	if err != nil {
		return nil, err
	}
	if len(a.Statements) != 1 || a.Statements[0].MayWrite || !a.Statements[0].ReturnsRows || (a.Statements[0].Kind != "select" && a.Statements[0].Kind != "setopr") {
		return nil, bad
	}
	p := &QueryPageSQL{Dialect: d, CTEs: map[string]bool{}}
	mode, err := mysql.GetSQLMode(options.MySQLSQLMode)
	if err != nil {
		return nil, err
	}
	// Only remove the single parser-proven statement's terminal delimiter. The
	// lexer is used for source positions, never to split or classify statements.
	source := a.Statements[0].SQL
	end := len(source)
	for _, t := range lexMode(source, d, mode.HasANSIQuotesMode(), mode.HasNoBackslashEscapesMode()) {
		if t.kind == 'c' && (strings.HasPrefix(t.text, "/*!") || strings.HasPrefix(t.text, "/*+") || strings.HasPrefix(strings.ToUpper(t.text), "/*M!")) {
			return nil, bad
		}
		if t.kind == ';' {
			end = t.start
			break
		}
	}
	p.Original = strings.TrimSpace(source[:end])
	if d == PostgreSQL {
		tree, err := pg.Parse(document)
		if err != nil {
			return nil, err
		}
		p.Ordered = len(tree.Stmts[0].Stmt.GetSelectStmt().SortClause) > 0
		unsafe := false
		var walk func(protoreflect.Message)
		walk = func(m protoreflect.Message) {
			switch m.Descriptor().Name() {
			case "IntoClause", "LockingClause", "InsertStmt", "UpdateStmt", "DeleteStmt", "MergeStmt", "ParamRef", "RangeFunction", "RangeTableFunc", "TableSampleClause":
				unsafe = true
			case "RangeVar":
				r := m.Interface().(*pg.RangeVar)
				p.Relations = append(p.Relations, QueryPageRelation{Schema: r.Schemaname, Name: r.Relname})
			case "CommonTableExpr":
				p.CTEs[m.Interface().(*pg.CommonTableExpr).Ctename] = true
			case "TypeCast":
				names := m.Interface().(*pg.TypeCast).TypeName.Names
				if len(names) == 1 {
					p.Types = append(p.Types, names[0].GetString_().GetSval())
				}
				if len(names) < 1 || len(names) > 2 || len(names) == 2 && names[0].GetString_().GetSval() != "pg_catalog" || !queryPageType(names[len(names)-1].GetString_().GetSval()) {
					unsafe = true
				}
			case "A_Expr":
				names := m.Interface().(*pg.A_Expr).Name
				if len(names) > 1 {
					unsafe = true
				}
				if len(names) == 1 {
					op := names[0].GetString_().GetSval()
					p.Operators = append(p.Operators, op)
					if !queryPageOperator(op) {
						unsafe = true
					}
				}
			case "SortBy":
				if len(m.Interface().(*pg.SortBy).UseOp) > 0 {
					unsafe = true
				}
			case "FuncCall":
				call := m.Interface().(*pg.FuncCall)
				names := call.Funcname
				if len(names) == 1 {
					p.Functions = append(p.Functions, names[0].GetString_().GetSval())
				}
				if len(names) < 1 || len(names) > 2 || len(names) == 2 && names[0].GetString_().GetSval() != "pg_catalog" || !queryPageFunction(names[len(names)-1].GetString_().GetSval()) {
					unsafe = true
				}
			}
			if unsafe {
				return
			}
			m.Range(func(f protoreflect.FieldDescriptor, v protoreflect.Value) bool {
				if f.Kind() == protoreflect.MessageKind {
					if f.IsList() {
						for i := 0; i < v.List().Len(); i++ {
							walk(v.List().Get(i).Message())
						}
					} else {
						walk(v.Message())
					}
				}
				return !unsafe
			})
		}
		walk(tree.ProtoReflect())
		if unsafe {
			return nil, bad
		}
	} else {
		if err := ValidateBrowseSQLMode(d, options); err != nil {
			return nil, err
		}
		parser := parser.New()
		parser.SetSQLMode(mode)
		nodes, _, err := parser.ParseSQL(document)
		if err != nil {
			return nil, err
		}
		guard := &queryPageGuard{query: p}
		nodes[0].Accept(guard)
		if guard.unsafe {
			return nil, bad
		}
		switch n := nodes[0].(type) {
		case *ast.SelectStmt:
			p.Ordered = n.OrderBy != nil
		case *ast.SetOprStmt:
			p.Ordered = n.OrderBy != nil
		}
	}
	return p, nil
}

// An explicit built-in set rejects sequence, locking, session and arbitrary
// stored functions. The worker also checks actual relation/type provenance and
// visible function/operator overloads before enabling or replaying a source.
func queryPageFunction(name string) bool {
	switch strings.ToLower(name) {
	case "count", "sum", "min", "max", "avg", "abs", "ceil", "ceiling", "floor", "round", "length", "char_length", "lower", "upper", "coalesce", "nullif", "greatest", "least", "concat", "substring", "substr", "trim", "ltrim", "rtrim", "replace", "row_number", "rank", "dense_rank", "lag", "lead", "first_value", "last_value":
		return true
	}
	return false
}

type queryPageGuard struct {
	unsafe bool
	query  *QueryPageSQL
}

func (g *queryPageGuard) Enter(n ast.Node) (ast.Node, bool) {
	switch x := n.(type) {
	case *ast.TableName:
		g.query.Relations = append(g.query.Relations, QueryPageRelation{Schema: x.Schema.O, Name: x.Name.O})
	case *ast.CommonTableExpression:
		g.query.CTEs[strings.ToLower(x.Name.O)] = true
	case *ast.SelectStmt:
		if x.SelectIntoOpt != nil || x.LockInfo != nil && x.LockInfo.LockType != ast.SelectLockNone || x.SelectStmtOpts != nil && x.CalcFoundRows {
			g.unsafe = true
		}
	case *ast.VariableExpr, ast.ParamMarkerExpr, *ast.TableOptimizerHint:
		g.unsafe = true
	case *ast.FuncCallExpr:
		if x.Schema.O != "" || !queryPageFunction(x.FnName.L) {
			g.unsafe = true
		}
	case *ast.AggregateFuncExpr:
		if !queryPageFunction(x.F) {
			g.unsafe = true
		}
	case *ast.WindowFuncExpr:
		if !queryPageFunction(x.Name) {
			g.unsafe = true
		}
	}
	return n, g.unsafe
}
func (g *queryPageGuard) Leave(n ast.Node) (ast.Node, bool) { return n, !g.unsafe }

func (p *QueryPageSQL) Page(offset, size int, columns []string) string {
	if p.Dialect == MySQL {
		// A derived table keeps the original LIMIT/OFFSET isolated from the UI
		// window. Positional aliases permit duplicate output names; the outer
		// projection restores their exact labels before masking/metadata binding.
		aliases, projection := make([]string, len(columns)), make([]string, len(columns))
		for i, name := range columns {
			aliases[i] = fmt.Sprintf("c%d", i)
			projection[i] = "__chen_query_page." + aliases[i] + " AS " + quoteIdentifier(name, MySQL, '`')
		}
		return fmt.Sprintf("SELECT %s FROM (\n%s\n) AS __chen_query_page(%s) LIMIT %d OFFSET %d", strings.Join(projection, ","), p.Original, strings.Join(aliases, ","), size, offset)
	}
	return fmt.Sprintf("SELECT * FROM (\n%s\n) AS \"__chen_query_page\" LIMIT %d OFFSET %d", p.Original, size, offset)
}
func (p *QueryPageSQL) Count(columns int) string {
	aliases := make([]string, columns)
	for i := range aliases {
		aliases[i] = fmt.Sprintf("c%d", i)
	}
	suffix := ""
	if columns > 0 {
		suffix = "(" + strings.Join(aliases, ",") + ")"
	}
	return "SELECT COUNT(*) FROM (\n" + p.Original + "\n) AS __chen_query_count" + suffix
}

func queryPageType(name string) bool {
	switch strings.ToLower(name) {
	case "int2", "int4", "int8", "smallint", "integer", "bigint", "numeric", "decimal", "float4", "float8", "real", "double precision", "text", "varchar", "bpchar", "char", "bool", "boolean", "date", "time", "timetz", "timestamp", "timestamptz", "interval", "json", "jsonb", "bytea", "uuid":
		return true
	}
	return false
}

func queryPageOperator(op string) bool {
	switch op {
	case "=", "<>", "!=", "<", ">", "<=", ">=", "+", "-", "*", "/", "%", "^", "||", "~~", "!~~", "~~*", "!~~*", "~", "!~", "~*", "!~*", "->", "->>", "#>", "#>>", "@>", "<@", "?", "?|", "?&", "BETWEEN", "NOT BETWEEN":
		return true
	}
	return false
}
