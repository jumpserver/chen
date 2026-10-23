package sqlservice

import (
	"fmt"
	"strings"

	"github.com/pingcap/tidb/pkg/parser/mysql"
)

// This scanner preserves original source spans and supports read queries only.
// It deliberately does not claim to parse every vendor grammar or guarantee
// absence of function side effects: permissions are enforced separately.
func analyzeSelectDocument(d Dialect, document string, options AnalyzeOptions) (Analysis, error) {
	out := Analysis{Statements: []Statement{}}
	mode, err := mysql.GetSQLMode(options.MySQLSQLMode)
	if err != nil {
		return out, err
	}
	tokens := lexMode(document, d, mode.HasANSIQuotesMode(), mode.HasNoBackslashEscapesMode())
	start, depth := -1, 0
	statement := []token{}
	appendStatement := func(end int) error {
		if len(statement) == 0 {
			return nil
		}
		first := statement[0]
		if !first.is("SELECT") && !first.is("WITH") {
			return fmt.Errorf("%s currently supports SELECT and WITH read queries; unsupported statement at byte %d", d, first.start)
		}
		hasSelect := false
		for i, t := range statement {
			if t.kind != 'i' || t.quoted {
				continue
			}
			if t.is("SELECT") {
				hasSelect = true
			}
			switch strings.ToUpper(t.value) {
			case "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "INTO", "OUTFILE", "DUMPFILE", "LOCK", "UNLOCK", "ATTACH", "DETACH", "SYSTEM", "GO", "DELIMITER":
				return fmt.Errorf("%s read queries do not support %s at byte %d", d, t.value, t.start)
			case "NEXTVAL", "SETVAL":
				return fmt.Errorf("sequence mutation is not supported in read queries")
			case "NEXT":
				if i+1 < len(statement) && statement[i+1].is("VALUE") {
					return fmt.Errorf("sequence mutation is not supported in read queries")
				}
			}
		}
		if !hasSelect {
			return fmt.Errorf("WITH query must contain SELECT")
		}
		out.Statements = append(out.Statements, Statement{SQL: document[start:end], Start: start, End: end, Kind: "select", ReturnsRows: true})
		statement = nil
		start = -1
		return nil
	}
	for _, t := range tokens {
		if t.kind == 'c' {
			if strings.HasPrefix(t.text, "/*") && !t.closed {
				return out, fmt.Errorf("unterminated SQL comment at byte %d", t.start)
			}
			// Executable comments are a MySQL/MariaDB server extension. The
			// native MySQL parser handles them; this fallback must not skip SQL.
			if strings.HasPrefix(t.text, "/*!") || strings.HasPrefix(t.text, "/*M!") {
				return out, fmt.Errorf("executable SQL comments require native parser support")
			}
			continue
		}
		if (t.quoted || t.kind == 's') && !t.closed {
			return out, fmt.Errorf("unterminated SQL quote at byte %d", t.start)
		}
		if t.kind == ';' {
			if depth != 0 {
				return out, fmt.Errorf("statement terminator inside parentheses")
			}
			if err := appendStatement(t.start); err != nil {
				return out, err
			}
			continue
		}
		if start < 0 {
			start = t.start
		}
		switch t.kind {
		case '(':
			depth++
			if depth > 128 {
				return out, fmt.Errorf("SQL nesting exceeds 128 levels")
			}
		case ')':
			depth--
			if depth < 0 {
				return out, fmt.Errorf("unmatched closing parenthesis at byte %d", t.start)
			}
		}
		statement = append(statement, t)
	}
	if depth != 0 {
		return out, fmt.Errorf("unclosed SQL parenthesis")
	}
	if err := appendStatement(len(document)); err != nil {
		return out, err
	}
	return out, nil
}
