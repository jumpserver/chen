package sqlservice

import (
	"errors"
	"strings"

	pg "github.com/pganalyze/pg_query_go/v6"
	"github.com/pingcap/tidb/pkg/parser"
	"github.com/pingcap/tidb/pkg/parser/ast"
	"github.com/pingcap/tidb/pkg/parser/mysql"
	"google.golang.org/protobuf/reflect/protoreflect"
)

// EstimatedPlanSQL validates the original statement using the physical console's
// parser options. It is an admission check, never an authorization or purity proof.
func EstimatedPlanSQL(d Dialect, document string, options AnalyzeOptions) (string, error) {
	switch d {
	case PostgreSQL, MySQL, MariaDB, OceanBaseMySQL, ClickHouse:
	default:
		return "", errors.New("explain_not_supported")
	}
	a, err := AnalyzeWithOptions(d, document, options)
	if err != nil || len(a.Statements) != 1 {
		return "", errors.New("explain_requires_single_select")
	}
	s := a.Statements[0]
	if s.MayWrite || !s.ReturnsRows || (s.Kind != "select" && s.Kind != "setopr") {
		return "", errors.New("explain_requires_single_select")
	}
	prefix := "EXPLAIN "
	switch {
	case d == PostgreSQL:
		tree, err := pg.Parse(document)
		if err != nil {
			return "", errors.New("explain_requires_single_select")
		}
		unsafe := false
		var visit func(protoreflect.Message)
		visit = func(m protoreflect.Message) {
			// Include nested SELECT INTO and locking clauses, not just the outer SELECT.
			if m.Descriptor().Name() == "IntoClause" || m.Descriptor().Name() == "LockingClause" {
				unsafe = true
			}
			m.Range(func(f protoreflect.FieldDescriptor, v protoreflect.Value) bool {
				if f.Kind() == protoreflect.MessageKind {
					if f.IsList() {
						for i := 0; i < v.List().Len(); i++ {
							visit(v.List().Get(i).Message())
						}
					} else {
						visit(v.Message())
					}
				}
				return !unsafe
			})
		}
		visit(tree.ProtoReflect())
		if unsafe {
			return "", errors.New("explain_unsafe_select")
		}
		prefix = "EXPLAIN (FORMAT JSON, ANALYZE FALSE) "
	case IsMySQLDialect(d):
		input, err := mysqlParserInput(document, options)
		if err != nil {
			return "", errors.New("explain_requires_single_select")
		}
		p := parser.New()
		mode, err := mysql.GetSQLMode(options.MySQLSQLMode)
		if err != nil {
			return "", errors.New("explain_requires_single_select")
		}
		p.SetSQLMode(mode)
		nodes, _, err := p.ParseSQL(input)
		if err == nil {
			guard := &planSelectGuard{}
			for _, n := range nodes {
				n.Accept(guard)
			}
			if guard.unsafe {
				return "", errors.New("explain_unsafe_select")
			}
		} else if d == MySQL {
			return "", errors.New("explain_requires_single_select")
		} else if _, err := planReadDocument(d, document, options); err != nil {
			return "", errors.New("explain_unsafe_select")
		}
		// Pin classic output independently of MySQL's session explain_format setting.
		if d == MySQL {
			prefix = "EXPLAIN FORMAT=TRADITIONAL "
		}
	default:
		if _, err := planReadDocument(d, document, options); err != nil {
			return "", errors.New("explain_unsafe_select")
		}
		prefix = "EXPLAIN PLAN "
	}
	return prefix + strings.TrimSpace(s.SQL), nil
}

type planSelectGuard struct{ unsafe bool }

func (g *planSelectGuard) Enter(n ast.Node) (ast.Node, bool) {
	if s, ok := n.(*ast.SelectStmt); ok && (s.SelectIntoOpt != nil || s.LockInfo != nil && s.LockInfo.LockType != ast.SelectLockNone) {
		g.unsafe = true
	}
	return n, g.unsafe
}
func (g *planSelectGuard) Leave(n ast.Node) (ast.Node, bool) { return n, !g.unsafe }

// Scanner dialects have no native locking AST; reject FOR SHARE as well as
// the write/INTO/locking forms already covered by analyzeSelectDocument.
func planReadDocument(d Dialect, document string, options AnalyzeOptions) (Analysis, error) {
	a, err := analyzeSelectDocument(d, document, options)
	if err != nil {
		return a, err
	}
	mode, _ := mysql.GetSQLMode(options.MySQLSQLMode)
	previousFor := false
	for _, token := range lexMode(document, d, mode.HasANSIQuotesMode(), mode.HasNoBackslashEscapesMode()) {
		if token.kind == 'c' {
			continue
		}
		if previousFor && token.is("SHARE") {
			return a, errors.New("explain_unsafe_select")
		}
		previousFor = token.is("FOR")
	}
	return a, nil
}
