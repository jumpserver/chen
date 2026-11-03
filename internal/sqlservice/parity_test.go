package sqlservice

import (
	"strings"
	"testing"
)

func TestParityAdministrativeAndProceduralSQL(t *testing.T) {
	for _, d := range []Dialect{Oracle, SQLServer, Dameng, DB2, OceanBaseOracle, ClickHouse} {
		for _, sql := range []string{
			"GRANT SELECT, UPDATE ON app_table TO app_reader",
			"REVOKE SELECT ON app_table FROM app_reader",
			"CREATE SEQUENCE app_sequence START WITH 1 INCREMENT BY 1",
			"DROP SEQUENCE app_sequence",
		} {
			if d == ClickHouse && strings.Contains(sql, "SEQUENCE") {
				continue
			}
			a, err := Analyze(d, sql)
			if err != nil || len(a.Statements) != 1 || a.Statements[0].SQL != sql || !a.Statements[0].MayWrite {
				t.Errorf("%s %q: %+v %v", d, sql, a, err)
			}
		}
	}
	for _, d := range []Dialect{Oracle, Dameng, OceanBaseOracle} {
		for _, sql := range []string{
			"BEGIN UPDATE app_table SET n=1; DELETE FROM app_table WHERE n=2; END;",
			"CREATE OR REPLACE PROCEDURE app_proc AS BEGIN UPDATE app_table SET n=1; END;",
		} {
			a, err := Analyze(d, sql)
			if err != nil || len(a.Statements) != 1 || len(a.Statements[0].PolicySQL) == 0 {
				t.Errorf("%s lost procedural boundary/policy: %+v %v", d, a, err)
			}
		}
	}
	for _, sql := range []string{
		"CREATE SCHEMA s AUTHORIZATION dbo CREATE TABLE s.hidden(id int)",
		"CREATE OR ALTER PROCEDURE app_proc AS BEGIN UPDATE app_table SET n=1; END",
		"BEGIN DELETE FROM app_table; END",
	} {
		a, err := Analyze(SQLServer, sql)
		if err != nil || len(a.Statements) != 1 || len(a.Statements[0].PolicySQL) == 0 {
			t.Errorf("T-SQL lost nested policy: %+v %v", a, err)
		}
	}
	if _, err := Analyze(SQLServer, "CREATE PROCEDURE p AS SELECT 1; DELETE FROM t"); err == nil {
		t.Fatal("routine body could become a separately executed DELETE")
	}
}

func TestParityConfirmedQueryShapesAcrossDialects(t *testing.T) {
	for _, d := range []Dialect{MySQL, MariaDB, PostgreSQL, SQLServer, Oracle, Dameng, DB2, ClickHouse, OceanBaseMySQL, OceanBaseOracle} {
		sql := "SELECT id, LOWER(name) AS label FROM app_view WHERE id IN (SELECT id FROM other_table) ORDER BY id"
		q, err := AnalyzeQueryPageForReexecution(d, sql, AnalyzeOptions{})
		if err != nil {
			t.Errorf("%s query: %v", d, err)
			continue
		}
		if q.Page(200, 201, []string{"id", "label"}) == "" || q.Count(2) == "" {
			t.Errorf("%s lost page/count", d)
		}
		where := "LOWER(name) = 'x' AND id IN (SELECT id FROM other_table)"
		cols := []EditColumn{{Name: "id", DataType: "integer"}, {Name: "name", DataType: "varchar"}}
		if err := ValidateBrowseWhereForReexecution(d, where, AnalyzeOptions{}, cols); err != nil {
			t.Errorf("%s predicate: %v", d, err)
		}
		for _, bad := range []string{
			"SELECT id FROM t; DELETE FROM t", "SELECT id FROM t FOR UPDATE",
			"WITH changed AS (DELETE FROM t RETURNING id) SELECT id FROM changed",
		} {
			if _, err := AnalyzeQueryPageForReexecution(d, bad, AnalyzeOptions{}); err == nil {
				t.Errorf("%s admitted non-replay query %q", d, bad)
			}
		}
		for _, bad := range []string{"1=1) FROM secret WHERE (1=1", "id=1) UNION SELECT (2"} {
			if err := ValidateBrowseWhereForReexecution(d, bad, AnalyzeOptions{}, cols); err == nil {
				t.Errorf("%s predicate escaped its WHERE: %s", d, bad)
			}
		}
		if d != ClickHouse {
			if _, err := AnalyzeQueryPageForReexecution(d, "SELECT app.custom_function(id) AS value FROM app_view", AnalyzeOptions{}); err != nil {
				t.Errorf("%s qualified function: %v", d, err)
			}
		}
	}
}

func TestParityConstraintQueriesStayBoundAndPermissionScoped(t *testing.T) {
	for _, d := range []Dialect{MySQL, MariaDB, PostgreSQL, SQLServer, Oracle, Dameng, DB2, OceanBaseMySQL, OceanBaseOracle} {
		q, names, ok := inspectionConstraints(d, "scope'private", "table'private")
		if !ok || len(names) != 3 || strings.Contains(q.SQL, "scope'private") || len(q.Args) != 2 {
			t.Errorf("%s lost constraints or parameter binding: %+v %v", d, q, names)
		}
		if !strings.Contains(q.SQL, "CHECK") || !strings.Contains(q.SQL, "UNIQUE") {
			t.Errorf("%s omitted constraint categories", d)
		}
		if d == PostgreSQL && (!strings.Contains(q.SQL, "has_column_privilege") || !strings.Contains(q.SQL, "has_schema_privilege")) {
			t.Fatal("PostgreSQL constraint definitions leaked hidden columns")
		}
	}
	if _, _, ok := inspectionConstraints(ClickHouse, "db", "t"); ok {
		t.Fatal("invented relational constraints")
	}
}
