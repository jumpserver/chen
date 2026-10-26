package sqlservice

import (
	"context"
	"database/sql/driver"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestEditableSourceDirectProjection(t *testing.T) {
	cases := []struct {
		d    Dialect
		sql  string
		opts AnalyzeOptions
		want EditableSource
	}{
		{PostgreSQL, `SELECT u.id, u.name AS display_name FROM public.users AS u WHERE u.id>0 ORDER BY u.id LIMIT 10`, AnalyzeOptions{}, EditableSource{Schema: "public", Table: "users", Alias: "u", Columns: []EditProjection{{"id", "id"}, {"name", "display_name"}}}},
		{PostgreSQL, `SELECT "姓名" AS "显示 名" FROM "模式"."用户"`, AnalyzeOptions{}, EditableSource{Schema: "模式", Table: "用户", Columns: []EditProjection{{"姓名", "显示 名"}}}},
		{MySQL, "SELECT t.* FROM `db`.`users` t WHERE name='JOIN FROM fake' LIMIT 10", AnalyzeOptions{}, EditableSource{Schema: "db", Table: "users", Alias: "t", Star: true}},
		{MySQL, `SELECT "id" FROM "db"."users"`, AnalyzeOptions{MySQLSQLMode: "ANSI_QUOTES"}, EditableSource{Schema: "db", Table: "users", Columns: []EditProjection{{"id", "id"}}}},
		{Oracle, `SELECT t.id, t.name display_name FROM app.users t WHERE ROWNUM<=20`, AnalyzeOptions{}, EditableSource{Schema: "APP", Table: "USERS", Alias: "T", Columns: []EditProjection{{"ID", "ID"}, {"NAME", "DISPLAY_NAME"}}}},
		{DB2, `SELECT * FROM app.users FETCH FIRST 20 ROWS ONLY`, AnalyzeOptions{}, EditableSource{Schema: "APP", Table: "USERS", Star: true}},
		{SQLServer, `SELECT TOP (200) [id], [Name] FROM [dbo].[Users]`, AnalyzeOptions{}, EditableSource{Schema: "dbo", Table: "Users", Columns: []EditProjection{{"id", "id"}, {"Name", "Name"}}}},
		{SQLServer, `SELECT TOP 10 u.* FROM dbo.users u`, AnalyzeOptions{}, EditableSource{Schema: "dbo", Table: "users", Alias: "u", Star: true}},
		{PostgreSQL, `/* header */ SELECT * FROM public.users; -- SELECT other`, AnalyzeOptions{}, EditableSource{Schema: "public", Table: "users", Star: true}},
	}
	for _, c := range cases {
		t.Run(string(c.d)+"/"+c.sql, func(t *testing.T) {
			got, err := AnalyzeEditableQuery(c.d, c.sql, c.opts)
			if err != nil || !reflect.DeepEqual(got, c.want) {
				t.Fatalf("source=%+v error=%v want=%+v", got, err, c.want)
			}
		})
	}
}

func TestEditableSourceRejectsAmbiguousOrComputedRows(t *testing.T) {
	queries := []string{
		`SELECT a.id FROM users a JOIN groups g ON g.id=a.id`,
		`SELECT * FROM users, groups`, `SELECT * FROM (SELECT * FROM users) u`,
		`WITH u AS (SELECT * FROM users) SELECT * FROM u`, `SELECT DISTINCT id FROM users`,
		`SELECT id FROM users GROUP BY id`, `SELECT id FROM users UNION SELECT id FROM other`,
		`SELECT id, count(*) FROM users`, `SELECT id+1 FROM users`, `SELECT id::text FROM users`,
		`SELECT id, id AS other_id FROM users`, `SELECT id AS x, name AS x FROM users`,
		`SELECT *, id FROM users`, `SELECT x.id FROM users u`, `SELECT x.* FROM users u`,
		`SELECT id FROM users WHERE id IN (SELECT id FROM other)`, `SELECT * FROM users FOR UPDATE`,
		`SELECT id FROM users; SELECT id FROM other`, `UPDATE users SET name='x' RETURNING id`,
		`SELECT id FROM otherdb.public.users`, `SELECT 'id' FROM users`, `SELECT 1 FROM users`,
	}
	for _, q := range queries {
		t.Run(q, func(t *testing.T) {
			if _, err := AnalyzeEditableQuery(PostgreSQL, q, AnalyzeOptions{}); !errors.Is(err, ErrNotEditable) {
				t.Fatalf("unsafe source accepted: %v", err)
			}
		})
	}
	for _, q := range []string{"SELECT /*!80000 DISTINCT */ id FROM users", "SELECT id FROM users /*!80000 UNION SELECT id FROM others */"} {
		if _, err := AnalyzeEditableQuery(MySQL, q, AnalyzeOptions{}); !errors.Is(err, ErrNotEditable) {
			t.Fatalf("executable comment accepted: %s", q)
		}
	}
}

func editablePGSteps(flags catalogStep) []catalogStep {
	allowed := catalogStep{rows: [][]driver.Value{{true, true, true}}}
	return []catalogStep{
		catalogBool(true), allowed, {rows: [][]driver.Value{{"r"}}}, catalogBool(true), allowed,
		{rows: [][]driver.Value{{"db", "public", "users", "id", "bigint", false, "", int64(1)}, {"db", "public", "users", "name", "text", true, "", int64(2)}, {"db", "public", "users", "computed", "integer", true, "", int64(3)}}},
		flags, {rows: [][]driver.Value{{"users_pk", "id", int64(1)}}},
	}
}

func TestLoadEditTableKeepsIdentityGeneratedAndNullableDistinct(t *testing.T) {
	flags := catalogStep{rows: [][]driver.Value{{"id", false, true, false}, {"name", false, false, true}, {"computed", true, false, true}}}
	conn, script := scriptedCatalogConn(t, editablePGSteps(flags)...)
	got, err := LoadEditTable(context.Background(), conn, PostgreSQL, Context{Database: "db"}, "public", "users")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got.PrimaryKey, []string{"id"}) || len(got.Columns) != 3 {
		t.Fatalf("metadata=%+v", got)
	}
	if !got.Columns[0].PrimaryKey || !got.Columns[0].Identity || got.Columns[0].Generated || got.Columns[0].Nullable {
		t.Fatalf("identity flags=%+v", got.Columns[0])
	}
	if !got.Columns[1].Nullable || !got.Columns[1].HasDefault || !got.Columns[1].Supported {
		t.Fatalf("ordinary flags=%+v", got.Columns[1])
	}
	if !got.Columns[2].Generated {
		t.Fatal("generated column became writable")
	}
	for _, call := range script.calls {
		assertCatalogParameters(t, call)
	}
	if strings.Contains(script.calls[2].sql, "?") || strings.Contains(script.calls[6].sql, "?") {
		t.Fatal("PostgreSQL metadata uses invalid bind markers")
	}
}

func TestLoadEditTableMissingGeneratedMetadataFailsClosed(t *testing.T) {
	flags := catalogStep{rows: [][]driver.Value{{"id", false, true, false}, {"name", false, false, true}}}
	conn, _ := scriptedCatalogConn(t, editablePGSteps(flags)...)
	_, err := LoadEditTable(context.Background(), conn, PostgreSQL, Context{Database: "db"}, "public", "users")
	if !errors.Is(err, ErrNotEditable) {
		t.Fatalf("incomplete flags were accepted: %v", err)
	}
}

func TestLoadEditTableRejectsViewsAndNontransactionalTables(t *testing.T) {
	for _, d := range []Dialect{PostgreSQL, MySQL} {
		t.Run(string(d), func(t *testing.T) {
			object := catalogStep{rows: [][]driver.Value{{true, true, true}}}
			kind := "v"
			schema := "public"
			if d == MySQL {
				object = catalogBool(true)
				kind = "MyISAM"
				schema = "db"
			}
			conn, script := scriptedCatalogConn(t, catalogBool(true), object, catalogStep{rows: [][]driver.Value{{kind}}})
			_, err := LoadEditTable(context.Background(), conn, d, Context{Database: "db"}, schema, "users")
			if !errors.Is(err, ErrNotEditable) || len(script.calls) != 3 {
				t.Fatalf("nontransactional/view accepted: %v", err)
			}
		})
	}
}

func TestEditMetadataQueriesBindIdentifiers(t *testing.T) {
	for _, d := range []Dialect{PostgreSQL, MySQL, MariaDB, OceanBaseMySQL, Oracle, SQLServer, Dameng, DB2, OceanBaseOracle} {
		for _, build := range []func(Dialect, CatalogColumnRequest) (catalogQuery, error){editBaseTableQuery, editColumnFlagsQuery} {
			q, err := build(d, CatalogColumnRequest{Schema: "schema'quoted", Table: "table;DROP"})
			if err != nil || len(q.Args) != 2 || strings.Contains(q.SQL, "schema'quoted") || strings.Contains(q.SQL, "table;DROP") {
				t.Fatalf("unbound identifiers: %s %+v %v", d, q, err)
			}
		}
	}
}

func TestEditTypeSupportIsConservative(t *testing.T) {
	for _, c := range []struct {
		d    Dialect
		typ  string
		want bool
	}{
		{PostgreSQL, "numeric(38,10)", true}, {PostgreSQL, "timestamp(6) with time zone", true},
		{MySQL, "bigint(20) unsigned", true}, {MySQL, "bit(8)", false}, {SQLServer, "bit", true},
		{PostgreSQL, "integer[]", false}, {PostgreSQL, "bytea", false}, {Oracle, "CLOB", false}, {Oracle, "TIMESTAMP WITH LOCAL TIME ZONE", false},
		{PostgreSQL, "uuid", true}, {PostgreSQL, "jsonb", true}, {SQLServer, "timestamp", false},
		{DB2, "XML", false}, {PostgreSQL, "custom_domain", false},
	} {
		if got := EditTypeSupported(c.d, c.typ); got != c.want {
			t.Errorf("%s %s: %v", c.d, c.typ, got)
		}
	}
}

func TestEditMySQLCanonicalTableRespectsServerCaseMode(t *testing.T) {
	for _, tc := range []struct {
		name string
		mode int64
		rows [][]driver.Value
		want string
	}{
		{"case_sensitive", 0, nil, ""},
		{"lowercase_storage", 1, [][]driver.Value{{"items"}}, "items"},
		{"preserved_case", 2, [][]driver.Value{{"Items"}}, "Items"},
		{"hidden", 1, nil, ""},
		{"ambiguous", 1, [][]driver.Value{{"Items"}, {"items"}}, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			steps := []catalogStep{{rows: [][]driver.Value{{tc.mode}}}}
			if tc.mode != 0 {
				steps = append(steps, catalogStep{rows: tc.rows})
			}
			conn, script := scriptedCatalogConn(t, steps...)
			got, err := editMySQLCanonicalTable(context.Background(), conn, "db", "ITEMS")
			if tc.want == "" && !errors.Is(err, ErrCatalogNotFound) || tc.want != "" && (err != nil || got != tc.want) {
				t.Fatalf("canonical=%q err=%v", got, err)
			}
			if len(script.calls) != len(steps) {
				t.Fatalf("case-sensitive lookup queried a case-folded target")
			}
			if tc.mode != 0 {
				q := script.calls[1]
				if !strings.Contains(q.sql, "BINARY LOWER(TABLE_NAME)=BINARY LOWER(?)") || strings.Contains(q.sql, "ITEMS") {
					t.Fatal("catalog lookup lost binary comparison or parameter binding")
				}
			}
		})
	}
}
