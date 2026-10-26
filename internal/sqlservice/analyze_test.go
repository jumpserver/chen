package sqlservice

import (
	"strings"
	"testing"
)

func TestAnalyzeOriginalSpans(t *testing.T) {
	tests := []struct {
		name    string
		dialect Dialect
		sql     string
		count   int
	}{
		{"mysql EOF", MySQL, "SELECT 1", 1},
		{"mysql strings", MySQL, "SELECT ';' AS value; SELECT 2;", 2},
		{"mysql CTE", MySQL, "WITH x AS (SELECT 1 AS n) SELECT n, row_number() OVER (ORDER BY n) FROM x;", 1},
		{"mysql routine", MySQL, "CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END; SELECT 3", 2},
		{"mysql comments", MySQL, "\n/* 中文 */ SELECT 'hello';\nSELECT 2;", 2},
		{"mysql empty semicolons", MySQL, ";; SELECT 1;;; SELECT 2;", 2},
		{"pg EOF", PostgreSQL, "SELECT 1", 1},
		{"pg dollar quote", PostgreSQL, "SELECT $tag$a;b$tag$; SELECT 2;", 2},
		{"pg nested comments", PostgreSQL, "SELECT 1 /* comment /* inner ; */ end */; SELECT 2;", 2},
		{"pg function", PostgreSQL, "CREATE FUNCTION f() RETURNS integer LANGUAGE plpgsql AS $$ BEGIN RETURN 1; END; $$; SELECT f();", 2},
		{"pg modifying CTE", PostgreSQL, "WITH x AS (DELETE FROM t WHERE n=1 RETURNING *) SELECT * FROM x;", 1},
		{"pg merge", PostgreSQL, "MERGE INTO t USING s ON t.id=s.id WHEN MATCHED THEN UPDATE SET n=s.n WHEN NOT MATCHED THEN INSERT VALUES(s.id,s.n);", 1},
		{"pg17 JSON table", PostgreSQL, `SELECT * FROM JSON_TABLE('{"a":[1,2]}', '$.a[*]' COLUMNS (x int PATH '$')) AS jt;`, 1},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := Analyze(tc.dialect, tc.sql)
			if err != nil {
				t.Fatal(err)
			}
			if len(got.Statements) != tc.count {
				t.Fatalf("count=%d want=%d: %+v", len(got.Statements), tc.count, got)
			}
			last := 0
			for _, s := range got.Statements {
				if s.Start < last || s.End > len(tc.sql) || s.End < s.Start {
					t.Fatalf("bad source span %+v", s)
				}
				if s.SQL != tc.sql[s.Start:s.End] {
					t.Fatalf("rewrote source: %q", s.SQL)
				}
				last = s.End
			}
		})
	}
}
func TestAnalyzeRejectsWholeInvalidDocument(t *testing.T) {
	for _, d := range []Dialect{MySQL, PostgreSQL} {
		for _, s := range []string{"SELECT 1; DROP", "SELECT FROM;", "SELECT 'unterminated", "SELECT 1\x00", "SELECT 1; invalid suffix"} {
			if _, e := Analyze(d, s); e == nil {
				t.Errorf("%s accepted %q", d, s)
			}
		}
	}
	for _, s := range []string{"DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$\nDELIMITER ;", "INSERT INTO t(n) VALUES(1) RETURNING n"} {
		if _, e := Analyze(MySQL, s); e == nil {
			t.Errorf("accepted unsupported %q", s)
		}
	}
	if _, err := Analyze(PostgreSQL, "CREATE FUNCTION f() RETURNS integer LANGUAGE plpgsql AS $$ BEGIN this is not valid; END; $$;"); err == nil {
		t.Fatal("accepted invalid PL/pgSQL body")
	}
}
func TestMySQLModesAndVersionComments(t *testing.T) {
	if _, err := AnalyzeWithOptions(MySQL, `SELECT "value" FROM "table";`, AnalyzeOptions{MySQLSQLMode: "ANSI_QUOTES"}); err != nil {
		t.Fatal(err)
	}
	sql := "/*!90000 SELECT FROM */; SELECT 2;"
	a, err := AnalyzeWithOptions(MySQL, sql, AnalyzeOptions{MySQLVersion: 80036})
	if err != nil {
		t.Fatal(err)
	}
	if len(a.Statements) != 1 {
		t.Fatalf("inactive versioned SQL not skipped: %+v", a)
	}
	if _, err := AnalyzeWithOptions(MySQL, sql, AnalyzeOptions{MySQLVersion: 90000}); err == nil {
		t.Fatal("active invalid versioned SQL accepted")
	}
	sql = "/*!80000 SELECT 1 */; SELECT 2;"
	a, err = AnalyzeWithOptions(MySQL, sql, AnalyzeOptions{MySQLVersion: 80036})
	if err != nil || len(a.Statements) != 2 {
		t.Fatalf("active comments: %+v %v", a, err)
	}
	if _, err := AnalyzeWithOptions(MySQL, `SELECT 'a\'; /*!90000 SELECT FROM */ SELECT 2;`, AnalyzeOptions{MySQLSQLMode: "NO_BACKSLASH_ESCAPES", MySQLVersion: 80036}); err != nil {
		t.Fatalf("NO_BACKSLASH_ESCAPES masking: %v", err)
	}
	if _, err := AnalyzeWithOptions(MySQL, `/*!100000 SELECT 1 */;`, AnalyzeOptions{MySQLVersion: 100000}); err != nil {
		t.Fatalf("six digit version: %v", err)
	}
}
func TestDocumentLimits(t *testing.T) {
	if _, e := Analyze(MySQL, strings.Repeat(" ", MaxDocumentBytes+1)); e != ErrDocumentTooLarge {
		t.Fatalf("limit: %v", e)
	}
	if _, e := Analyze("sqlite", "select 1"); e != ErrUnsupportedDialect {
		t.Fatal(e)
	}
}

func TestStatementResultAndWriteClassification(t *testing.T) {
	tests := []struct {
		d           Dialect
		sql         string
		rows, write bool
	}{
		{PostgreSQL, "SELECT 1", true, false},
		{PostgreSQL, "INSERT INTO t VALUES (1)", false, true},
		{PostgreSQL, "INSERT INTO t VALUES (1) RETURNING *", true, true},
		{PostgreSQL, "UPDATE t SET n=1 RETURNING n", true, true},
		{PostgreSQL, "DELETE FROM t RETURNING *", true, true},
		{PostgreSQL, "WITH changed AS (DELETE FROM t RETURNING *) SELECT * FROM changed", true, true},
		{PostgreSQL, "SELECT * INTO t2 FROM t", false, true},
		{PostgreSQL, "SHOW search_path", true, false},
		{PostgreSQL, "BEGIN", false, false},
		{PostgreSQL, "SET search_path TO public", false, false},
		{PostgreSQL, "CREATE TABLE t(n int)", false, true},
		{PostgreSQL, "CALL p()", true, true},
		{MySQL, "SELECT 1", true, false},
		{MySQL, "SHOW TABLES", true, false},
		{MySQL, "INSERT INTO t VALUES(1)", false, true},
		{MySQL, "SELECT n FROM t INTO OUTFILE '/tmp/out.txt'", false, true},
		{MySQL, "EXPLAIN SELECT * FROM t", true, false},
		{MySQL, "BEGIN", false, false},
		{MySQL, "CALL p()", true, true},
	}
	for _, tc := range tests {
		t.Run(string(tc.d)+" "+tc.sql, func(t *testing.T) {
			a, e := Analyze(tc.d, tc.sql)
			if e != nil {
				t.Fatal(e)
			}
			s := a.Statements[0]
			if s.ReturnsRows != tc.rows || s.MayWrite != tc.write {
				t.Fatalf("capabilities %+v want rows=%v write=%v", s, tc.rows, tc.write)
			}
		})
	}
}
