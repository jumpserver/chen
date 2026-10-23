package sqlservice

import (
	"context"
	"strings"
	"testing"
)

var completionDialects = []Dialect{MySQL, MariaDB, PostgreSQL, Oracle, SQLServer, DB2, Dameng, ClickHouse, OceanBaseMySQL, OceanBaseOracle}

// Match catalog objects exactly so incorrect dialect folding cannot pass by
// accident through a case-insensitive fixture. This does not model server ACLs.
type dialectCatalog struct {
	schema    string
	relations []Relation
	columns   map[string][]Column
	lookups   []ColumnRequest
}

func (f *dialectCatalog) Relations(_ context.Context, _ MetadataRequest) ([]Relation, error) {
	return f.relations, nil
}
func (f *dialectCatalog) Columns(_ context.Context, r ColumnRequest) ([]Column, error) {
	f.lookups = append(f.lookups, r)
	if r.Schema != "" && r.Schema != f.schema {
		return nil, nil
	}
	return f.columns[r.Table], nil
}
func dialectName(d Dialect, name string) string {
	switch d {
	case Oracle, DB2, Dameng, OceanBaseOracle:
		return strings.ToUpper(name)
	default:
		return name
	}
}
func newDialectCatalog(d Dialect) *dialectCatalog {
	name := func(value string) string { return dialectName(d, value) }
	return &dialectCatalog{
		schema: name("app"),
		relations: []Relation{
			{Schema: name("app"), Name: name("users"), Kind: "table"},
			{Schema: name("app"), Name: name("orders"), Kind: "table"},
		},
		columns: map[string][]Column{
			name("users"):  {{Name: name("id"), DataType: "bigint"}, {Name: name("name"), DataType: "text"}},
			name("orders"): {{Name: name("id"), DataType: "bigint"}, {Name: name("user_id"), DataType: "bigint"}},
		},
	}
}
func completeDialect(t *testing.T, d Dialect, marked string, catalog Catalog) CompletionResponse {
	t.Helper()
	at := strings.IndexByte(marked, '|')
	if at < 0 {
		t.Fatal("missing cursor")
	}
	document := marked[:at] + marked[at+1:]
	position := byteToOffset(document, at)
	result, err := CompletionWithCatalog(context.Background(), d, CompletionRequest{
		Document: document, UTF16Offset: position, Version: 81,
		Context: Context{Database: "configured", Schema: dialectName(d, "app")},
	}, catalog)
	if err != nil {
		t.Fatal(err)
	}
	if result.Version != 81 || result.From > position || result.To < position || result.To > byteToOffset(document, len(document)) {
		t.Fatalf("invalid version/cursor range: %+v", result)
	}
	return result
}

func TestAllDialectCompletionContexts(t *testing.T) {
	for _, d := range completionDialects {
		t.Run(string(d), func(t *testing.T) {
			name := func(value string) string { return dialectName(d, value) }
			tests := []struct {
				name, sql    string
				want, reject []string
			}{
				{"table", "SELECT * FROM us|", []string{name("users")}, []string{name("orders"), name("id")}},
				{"field and alias", "SELECT u.| FROM users u", []string{name("id"), name("name")}, []string{name("user_id")}},
				{"JOIN table", "SELECT * FROM users u LEFT JOIN or| o ON u.id=o.user_id", []string{name("orders")}, []string{name("id")}},
				{"JOIN ON", "SELECT * FROM users u JOIN orders o ON |", []string{name("u") + "." + name("id"), name("o") + "." + name("user_id")}, []string{name("id"), name("users")}},
				{"ambiguous field", "SELECT i| FROM users u JOIN orders o ON u.id=o.user_id", []string{name("u") + "." + name("id"), name("o") + "." + name("id")}, []string{name("id")}},
				{"CTE explicit", "WITH chosen(selected_id) AS (SELECT id FROM users) SELECT c.| FROM chosen c", []string{name("selected_id")}, []string{name("id"), name("name")}},
				{"CTE projection", "WITH chosen AS (SELECT id AS selected_id, name FROM users) SELECT c.| FROM chosen c", []string{name("selected_id"), name("name")}, []string{name("id")}},
				{"derived table", "SELECT d.| FROM (SELECT id AS selected_id FROM users) d", []string{name("selected_id")}, []string{name("id")}},
				{"alias shadowing", "SELECT u.id FROM users u WHERE EXISTS (SELECT u.| FROM orders u)", []string{name("user_id")}, []string{name("name")}},
				{"schema qualified", "SELECT app.users.|", []string{name("id"), name("name")}, []string{name("user_id")}},
				{"projection alias", "SELECT id AS chosen FROM users ORDER BY ch|", []string{name("chosen")}, nil},
			}
			for _, tc := range tests {
				t.Run(tc.name, func(t *testing.T) {
					result := completeDialect(t, d, tc.sql, newDialectCatalog(d))
					requireLabels(t, result, tc.want...)
					rejectLabels(t, result, tc.reject...)
				})
			}
			for _, sql := range []string{"SELECT missing.| FROM users u", "SELECT 'FROM orders |' FROM users", "SELECT /* FROM orders | */ 1", "SELECT * FROM users AS |"} {
				if result := completeDialect(t, d, sql, newDialectCatalog(d)); len(result.Items) != 0 {
					t.Errorf("unexpected suggestions in %q: %v", sql, labels(result))
				}
			}
		})
	}
}

func dialectQuote(d Dialect, name string) string {
	switch d {
	case SQLServer:
		return "[" + strings.ReplaceAll(name, "]", "]]") + "]"
	case MySQL, MariaDB, ClickHouse, OceanBaseMySQL:
		return "`" + strings.ReplaceAll(name, "`", "``") + "`"
	default:
		return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
	}
}

func TestAllDialectQuotedNamesAndUTF16(t *testing.T) {
	for _, d := range completionDialects {
		t.Run(string(d), func(t *testing.T) {
			table, column := "Table.数据库 ] ` \"", "Field.😀 ] ` \""
			catalog := newDialectCatalog(d)
			catalog.columns[table] = []Column{{Name: column, DataType: "text"}}
			result := completeDialect(t, d, "SELECT '😀', u.| FROM "+dialectQuote(d, table)+" u", catalog)
			item := findCompletion(t, result, column)
			if item.InsertText != dialectQuote(d, column) {
				t.Fatalf("unsafe identifier insertion: got %q want %q", item.InsertText, dialectQuote(d, column))
			}
			if len(catalog.lookups) != 1 || catalog.lookups[0].Table != table {
				t.Fatalf("quoted name changed before lookup: %+v", catalog.lookups)
			}
			quoted := dialectQuote(d, column)
			marked := "SELECT '😀', u." + quoted[:1] + "Field|" + quoted[1+len("Field"):] + " FROM " + dialectQuote(d, table) + " u"
			result = completeDialect(t, d, marked, catalog)
			item = findCompletion(t, result, column)
			if item.InsertText != quoted || result.To-result.From != byteToOffset(quoted, len(quoted)) {
				t.Fatalf("replacement must cover complete UTF16 quoted token: %+v", result)
			}
		})
	}
}

func TestExtendedDialectIdentifierFolding(t *testing.T) {
	for _, d := range []Dialect{Oracle, Dameng, DB2, OceanBaseOracle} {
		t.Run(string(d), func(t *testing.T) {
			catalog := newDialectCatalog(d)
			catalog.columns["MixedCase"] = []Column{{Name: "MixedField"}}
			result := completeDialect(t, d, `SELECT u.| FROM app.users u`, catalog)
			requireLabels(t, result, "ID", "NAME")
			if catalog.lookups[0].Schema != "APP" || catalog.lookups[0].Table != "USERS" {
				t.Fatalf("unquoted identifiers not folded: %+v", catalog.lookups)
			}
			result = completeDialect(t, d, `SELECT "Alias".| FROM "MixedCase" "Alias"`, catalog)
			if got := findCompletion(t, result, "MixedField").InsertText; got != `"MixedField"` {
				t.Fatalf("mixed-case identifier lost quoting: %q", got)
			}
			result = completeDialect(t, d, `SELECT alias.| FROM "MixedCase" "Alias"`, catalog)
			if len(result.Items) != 0 {
				t.Fatalf("unquoted alias incorrectly matches mixed-case quoted alias: %v", labels(result))
			}
		})
	}
	catalog := newDialectCatalog(ClickHouse)
	catalog.columns["Users"] = []Column{{Name: "UserID"}}
	requireLabels(t, completeDialect(t, ClickHouse, "SELECT U.| FROM Users U", catalog), "UserID")
	if result := completeDialect(t, ClickHouse, "SELECT u.| FROM Users U", catalog); len(result.Items) != 0 {
		t.Fatalf("ClickHouse alias casing was ignored: %v", labels(result))
	}
	requireLabels(t, completeDialect(t, ClickHouse, "SELECT U.| FROM users U", catalog), "id", "name")
	rejectLabels(t, completeDialect(t, ClickHouse, "SELECT U.| FROM users U", catalog), "UserID")
}

func TestSQLServerBracketAndTopCompletion(t *testing.T) {
	catalog := newDialectCatalog(SQLServer)
	catalog.columns["Odd]Table"] = []Column{{Name: "Odd]Field"}}
	result := completeDialect(t, SQLServer, "SELECT [Alias]].x].[Odd]]|Field] FROM [app].[Odd]]Table] AS [Alias]].x]", catalog)
	if got := findCompletion(t, result, "Odd]Field").InsertText; got != "[Odd]]Field]" {
		t.Fatalf("bracket quote escape lost: %q", got)
	}
	for _, top := range []string{"TOP (10)", "TOP 10", "TOP (10) PERCENT", "TOP 10 PERCENT WITH TIES"} {
		requireLabels(t, completeDialect(t, SQLServer, "WITH sample AS (SELECT "+top+" u.* FROM users u) SELECT s.| FROM sample s", catalog), "id", "name")
	}
}

func TestOracleQStringLexicalIsolation(t *testing.T) {
	for _, d := range []Dialect{Oracle, Dameng, OceanBaseOracle} {
		for _, literal := range []string{"q'[it's ; FROM orders u]'", "Q'{it's ; FROM orders u}'", "q'(it's ; FROM orders u)'", "q'<it's ; FROM orders u>'", "q'!it's ; FROM orders u!'"} {
			t.Run(string(d)+literal, func(t *testing.T) {
				result := completeDialect(t, d, "SELECT u.|, "+literal+" FROM users u", newDialectCatalog(d))
				requireLabels(t, result, "ID", "NAME")
				rejectLabels(t, result, "USER_ID")
				marked := strings.Replace(literal, "FROM", "FR|OM", 1)
				if result := completeDialect(t, d, "SELECT "+marked+" FROM users u", newDialectCatalog(d)); len(result.Items) != 0 {
					t.Fatalf("suggestions inside Oracle q-string: %v", labels(result))
				}
				analysis, err := Analyze(d, "SELECT "+literal+" FROM DUAL; SELECT 2 FROM DUAL;")
				if err != nil || len(analysis.Statements) != 2 {
					t.Fatalf("q-string split a statement: %+v %v", analysis, err)
				}
			})
		}
	}
}

func TestOracleNationalQStringAndMinusScope(t *testing.T) {
	catalog := newDialectCatalog(Oracle)
	for _, literal := range []string{"nq'[it's ; FROM orders u]'", "NQ'!it's ; FROM orders u!'", "q'界it's ; FROM orders u界'"} {
		t.Run(literal, func(t *testing.T) {
			result := completeDialect(t, Oracle, "SELECT u.|, "+literal+" FROM users u", catalog)
			requireLabels(t, result, "ID", "NAME")
			rejectLabels(t, result, "USER_ID")
			analysis, err := Analyze(Oracle, "SELECT "+literal+" FROM DUAL; SELECT 2 FROM DUAL;")
			if err != nil || len(analysis.Statements) != 2 {
				t.Fatalf("Oracle national/multibyte q-string split: %+v %v", analysis, err)
			}
		})
	}
	result := completeDialect(t, Oracle, "SELECT u.id FROM users u MINUS SELECT o.| FROM orders o", catalog)
	requireLabels(t, result, "ID", "USER_ID")
	rejectLabels(t, result, "NAME")
	if result := completeDialect(t, Oracle, "SELECT u.id FROM users u MINUS SELECT u.| FROM orders o", catalog); len(result.Items) != 0 {
		t.Fatalf("MINUS preceding alias leaked: %v", labels(result))
	}
}

func TestAllDialectReadQuerySourceSpans(t *testing.T) {
	for _, d := range completionDialects {
		t.Run(string(d), func(t *testing.T) {
			document := "\n/* 中文😀 */ SELECT 'semi;''😀' AS value FROM users;\n-- boundary\nWITH chosen AS (SELECT id FROM users) SELECT id FROM chosen;\n"
			analysis, err := Analyze(d, document)
			if err != nil || len(analysis.Statements) != 2 {
				t.Fatalf("analysis: %+v %v", analysis, err)
			}
			last := 0
			for _, statement := range analysis.Statements {
				if statement.Start < last || statement.End < statement.Start || statement.End > len(document) || statement.SQL != document[statement.Start:statement.End] {
					t.Fatalf("source was rewritten or overlapping: %+v", statement)
				}
				if !statement.ReturnsRows || statement.MayWrite {
					t.Fatalf("read query capabilities: %+v", statement)
				}
				last = statement.End
			}
			for _, invalid := range []string{"SELECT 1\x00", "SELECT '\xff'", strings.Repeat(" ", MaxDocumentBytes+1)} {
				if _, err := Analyze(d, invalid); err == nil {
					t.Fatal("accepted invalid encoding or unbounded document")
				}
			}
		})
	}
}

func TestSelectScannerRejectsWritesAndBrokenBatches(t *testing.T) {
	for _, d := range completionDialects {
		t.Run(string(d), func(t *testing.T) {
			for _, document := range []string{
				"DELETE FROM users", "SELECT 1; UPDATE users SET id=1", "SELECT 1; DROP TABLE users",
				"WITH x AS (DELETE FROM users) SELECT * FROM x", "SELECT * INTO copy_users FROM users",
				"SELECT * FROM users FOR UPDATE", "SELECT seq.NEXTVAL FROM DUAL", "SELECT NEXT VALUE FOR seq",
				"SELECT 1; EXEC procedure_name", "SELECT 1\nGO\nSELECT 2", "SELECT 1; invalid suffix",
				"SELECT 'unterminated", "SELECT \"unterminated", "SELECT 1 /* unfinished", "SELECT (1", "SELECT 1)", "SELECT (1; 2)",
				"/*! SELECT 1 */ SELECT 2", "/*M! DELETE FROM users */ SELECT 2",
			} {
				if _, err := analyzeSelectDocument(d, document, AnalyzeOptions{}); err == nil {
					t.Errorf("scanner accepted %q", document)
				}
			}
			// Word-looking data is allowed. The scanner is lexical and intentionally
			// leaves complete vendor expression grammar to the database server.
			if _, err := analyzeSelectDocument(d, "SELECT 'DELETE; UPDATE NEXTVAL INTO' AS value /* DROP TABLE users */ FROM users", AnalyzeOptions{}); err != nil {
				t.Fatalf("data/comment mistaken for mutation: %v", err)
			}
		})
	}
}
