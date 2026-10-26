package sqlservice

import (
	"strings"
	"testing"
)

func TestVendorWriteCommands(t *testing.T) {
	for _, d := range []Dialect{SQLServer, Oracle, Dameng, DB2, ClickHouse, OceanBaseOracle} {
		t.Run(string(d), func(t *testing.T) {
			query := "CREATE TABLE \"chen_write\" (\"id\" INT PRIMARY KEY, \"value\" VARCHAR(60));\n" +
				"INSERT INTO \"chen_write\" VALUES(1, '中文😀;DELETE FROM anything');\n" +
				"UPDATE \"chen_write\" SET \"value\"='O''Reilly' WHERE \"id\"=1;\n" +
				"DELETE FROM \"chen_write\" WHERE \"id\"=1;\n" +
				"ALTER TABLE \"chen_write\" ADD \"note\" VARCHAR(60); DROP TABLE \"chen_write\";"
			a, err := Analyze(d, query)
			if err != nil || len(a.Statements) != 6 {
				t.Fatalf("write script: %+v %v", a, err)
			}
			for _, s := range a.Statements {
				if !s.MayWrite || s.ReturnsRows || s.SQL != query[s.Start:s.End] {
					t.Fatalf("invalid dispatch/source: %+v", s)
				}
			}
			read, err := Analyze(d, "SELECT id FROM users ORDER BY id DESC")
			if err != nil || !read.Statements[0].ReturnsRows || read.Statements[0].MayWrite {
				t.Fatalf("read regression: %+v %v", read, err)
			}
		})
	}
}

func TestVendorCommandResultDispatch(t *testing.T) {
	for _, tc := range []struct {
		d           Dialect
		sql         string
		rows, write bool
	}{
		{SQLServer, "INSERT INTO t(id) OUTPUT inserted.id VALUES(1)", true, true},
		{SQLServer, "UPDATE t SET id=2 OUTPUT inserted.id WHERE id=1", true, true},
		{SQLServer, "DELETE FROM t OUTPUT deleted.id INTO [backup] WHERE id=1", false, true},
		{SQLServer, "WITH v AS (SELECT 1 AS n) UPDATE t SET id=v.n FROM v WHERE t.id=1", false, true},
		{SQLServer, "SELECT id INTO [backup] FROM t", false, true},
		{SQLServer, "BEGIN TRANSACTION", false, false},
		{SQLServer, "COMMIT", false, false},
		{Oracle, "ROLLBACK TO SAVEPOINT chen_edit", false, false},
		{Oracle, "UPDATE t SET n=2 RETURNING id INTO :out", false, true},
		{DB2, "SELECT id FROM FINAL TABLE (INSERT INTO t VALUES(1))", true, true},
		{ClickHouse, "ALTER TABLE t UPDATE title='saved' WHERE id=1", false, true},
		{ClickHouse, "ALTER TABLE t DELETE WHERE id=1", false, true},
		{Oracle, "SELECT seq.NEXTVAL FROM DUAL", true, true},
		{Oracle, "SELECT id FROM t FOR UPDATE", true, true},
		{Oracle, "INSERT INTO t SELECT id FROM s UNION ALL SELECT id FROM s2", false, true},
		{Oracle, "CREATE VIEW v AS SELECT id FROM t", false, true},
		{Oracle, "SELECT REPLACE(name, 'a', 'b') FROM t", true, true},
		{MariaDB, "INSERT INTO t VALUES(1) RETURNING id", true, true},
	} {
		t.Run(string(tc.d)+"_"+tc.sql, func(t *testing.T) {
			a, err := Analyze(tc.d, tc.sql)
			if err != nil || len(a.Statements) != 1 {
				t.Fatalf("analyze: %+v %v", a, err)
			}
			if a.Statements[0].ReturnsRows != tc.rows || a.Statements[0].MayWrite != tc.write {
				t.Fatalf("dispatch: %+v", a.Statements[0])
			}
		})
	}
}

func TestVendorScannerRequiresPolicyVisibleBoundaries(t *testing.T) {
	for _, d := range []Dialect{SQLServer, Oracle, Dameng, DB2, ClickHouse, OceanBaseOracle} {
		for _, text := range []string{
			"SELECT 1 UPDATE t SET id=2", "UPDATE t SET id=2 DELETE FROM t", "SELECT 1\nGO\nSELECT 2",
			"CREATE PROCEDURE p AS SELECT 1; DELETE FROM t", "BEGIN DELETE FROM t; END", "INSERT INTO t VALUES ('unfinished)",
			"UPDATE t SET n=(1; DELETE FROM t)", "DELETE FROM t /* unfinished", "SELECT 1; invalid trailing command",
		} {
			if a, err := Analyze(d, text); err == nil {
				t.Errorf("%s accepted unproven script %q: %+v", d, text, a)
			}
		}
		// Quoted literals, identifiers and comments cannot introduce a command.
		a, err := Analyze(d, "UPDATE t SET \"delete\"='UPDATE; DROP' /* DELETE FROM t */ WHERE id=1; SELECT 'GO' FROM t")
		if err != nil || len(a.Statements) != 2 {
			t.Fatalf("%s literal boundary: %+v %v", d, a, err)
		}
	}
	for _, d := range []Dialect{Oracle, Dameng, OceanBaseOracle} {
		a, err := Analyze(d, "UPDATE t SET n=q'[it''s; DELETE FROM t]' WHERE id=1; SELECT 1 FROM DUAL")
		if err != nil || len(a.Statements) != 2 || !strings.Contains(a.Statements[0].SQL, "DELETE") {
			t.Fatalf("Oracle quote split: %+v %v", a, err)
		}
	}
}
