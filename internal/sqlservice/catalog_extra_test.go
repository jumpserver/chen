package sqlservice

import (
	"context"
	"database/sql/driver"
	"errors"
	"strings"
	"testing"
)

func extraDialects() []Dialect {
	return []Dialect{SQLServer, Oracle, Dameng, DB2, ClickHouse, OceanBaseOracle}
}

func TestExtraCatalogQueriesBindIdentifiersAndCursors(t *testing.T) {
	for _, d := range extraDialects() {
		t.Run(string(d), func(t *testing.T) {
			malicious := "object'; DROP TABLE x; --"
			q, err := extraObjectQuery(d, CatalogObjectRequest{Database: "db", Schema: malicious, Query: "a_%!", AfterSchema: "schema'", AfterName: malicious}, true, 201)
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(q.SQL, malicious) || strings.Contains(q.SQL, "a_%!") || strings.Contains(q.SQL, "schema'") {
				t.Fatal("user input became executable SQL")
			}
			found := 0
			for _, a := range q.Args {
				if a == malicious {
					found++
				}
			}
			if found != 2 {
				t.Fatalf("schema and cursor not independently bound: %+v", q.Args)
			}
			if !strings.Contains(q.SQL, "201") || !strings.Contains(q.SQL, "ORDER BY") {
				t.Fatal("missing bounded ordered pagination")
			}
			if d == SQLServer && !strings.Contains(q.SQL, "@p1") || d == Oracle && !strings.Contains(q.SQL, ":1") || (d == Dameng || d == OceanBaseOracle) && !strings.Contains(q.SQL, "?") {
				t.Fatal("incorrect driver placeholder")
			}
			if d == ClickHouse && strings.Contains(q.SQL, "ESCAPE") {
				t.Fatal("ClickHouse does not support LIKE ESCAPE syntax")
			}
			columns, err := extraColumnQuery(d, CatalogColumnRequest{Database: "db", Schema: "schema'", Table: malicious, AfterOrdinal: 17}, 201)
			if err != nil || strings.Contains(columns.SQL, malicious) || strings.Contains(columns.SQL, "schema'") {
				t.Fatalf("unsafe column query: %v", err)
			}
		})
	}
}

func TestExtraCatalogUsesVisibleObjectsAndActualColumnMetadata(t *testing.T) {
	for _, d := range extraDialects() {
		t.Run(string(d), func(t *testing.T) {
			q, err := extraObjectQuery(d, CatalogObjectRequest{Database: "db", Schema: "app"}, false, 201)
			if err != nil {
				t.Fatal(err)
			}
			if d == SQLServer && !strings.Contains(q.SQL, "sys.objects") || IsOracleDialect(d) && !strings.Contains(q.SQL, "ALL_TABLES") || d == ClickHouse && !strings.Contains(q.SQL, "FROM system.tables") {
				t.Fatal("query lost native metadata visibility")
			}
			if d == DB2 {
				for _, required := range []string{"SYSCAT.TABAUTH", "AUTH_LIST_ROLES_FOR_AUTHID", "AUTH_LIST_GROUPS_FOR_AUTHID", "AUTH_LIST_AUTHORITIES_FOR_AUTHID", "DATAACCESS"} {
					if !strings.Contains(q.SQL, required) {
						t.Fatalf("Db2 metadata permission filter missing %s", required)
					}
				}
			}
			if IsOracleDialect(d) && strings.Contains(q.SQL, "ORACLE_MAINTAINED") != (d == Oracle) {
				t.Fatal("Oracle maintained-account filter must not assume DM/OB dictionary columns")
			}
			columns, _ := extraColumnQuery(d, CatalogColumnRequest{Database: "db", Schema: "app", Table: "t"}, 2000)
			if !strings.Contains(columns.SQL, "2000") || !strings.Contains(columns.SQL, "ORDER BY") {
				t.Fatal("completion columns not bounded and ordered")
			}
		})
	}
}

func TestExtraCatalogRejectsDatabaseChangesBeforeMetadata(t *testing.T) {
	for _, d := range []Dialect{SQLServer, DB2, ClickHouse} {
		t.Run(string(d), func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogStep{rows: [][]driver.Value{{"authorized"}}})
			_, err := SchemaRows(context.Background(), conn, d, "other", "", 20)
			if !errors.Is(err, ErrCatalogForbidden) || len(script.calls) != 1 {
				t.Fatalf("cross database access not rejected: %v, %d queries", err, len(script.calls))
			}
		})
	}
}

func TestOracleFamilyServiceAliasesAndNullableComments(t *testing.T) {
	for _, d := range []Dialect{Oracle, Dameng, OceanBaseOracle} {
		t.Run(string(d), func(t *testing.T) {
			conn, script := scriptedCatalogConn(t, catalogStep{rows: [][]driver.Value{{"app-service", "APP", "EVENTS", "table", "table", nil}}})
			objects, err := ObjectRows(context.Background(), conn, d, CatalogObjectRequest{Database: "app-service", Schema: "APP", Limit: 1})
			if err != nil || len(objects) != 1 || objects[0].Comment != "" || objects[0].Database != "app-service" {
				t.Fatalf("service alias/null comment lost: %+v, %v", objects, err)
			}
			if len(script.calls) != 1 || strings.Contains(script.calls[0].sql, "DB_NAME") {
				t.Fatal("service alias incorrectly compared to database name")
			}
			assertCatalogParameters(t, script.calls[0], "app-service", "APP")
			conn, script = scriptedCatalogConn(t,
				catalogStep{rows: [][]driver.Value{{"EVENTS"}}},
				catalogStep{rows: [][]driver.Value{{"app-service", "APP", "EVENTS", "BODY", "CLOB", int64(1), nil, int64(7)}}})
			columns, err := ColumnRows(context.Background(), conn, d, CatalogColumnRequest{Database: "app-service", Schema: "APP", Table: "EVENTS", AfterOrdinal: 6, Limit: 1})
			if err != nil || len(columns) != 1 || columns[0].DataType != "CLOB" || !columns[0].Nullable || columns[0].Ordinal != 7 || columns[0].Comment != "" {
				t.Fatalf("native column metadata lost: %+v, %v", columns, err)
			}
			if len(script.calls) != 2 || !hasCatalogArgument(script.calls[1], int64(6)) {
				t.Fatal("missing object visibility/column keyset query")
			}
		})
	}
}

func TestExtraCompletionMetadataHonorsSchemaAndNullComments(t *testing.T) {
	conn, script := scriptedCatalogConn(t, catalogStep{rows: [][]driver.Value{{"service", "APP", "USERS", "table", "table", nil}}})
	items, err := Metadata(context.Background(), conn, Oracle, MetadataRequest{Context: Context{Database: "service", Schema: "APP"}, Prefix: "us", Limit: 15})
	if err != nil || len(items) != 1 || items[0].Kind != "BASE TABLE" || items[0].Comment != "" {
		t.Fatalf("completion metadata mismatch: %+v %v", items, err)
	}
	if !hasCatalogArgument(script.calls[0], "APP") || !hasCatalogArgument(script.calls[0], "us%") || !strings.Contains(script.calls[0].sql, "ROWNUM <= 15") {
		t.Fatal("completion schema/prefix/limit lost")
	}
}

func TestClickHouseLikeEscaping(t *testing.T) {
	if got := clickHouseLike(`a_%\b`); got != `a\_\%\\b` {
		t.Fatalf("literal wildcard escaping failed: %q", got)
	}
	q, err := extraSchemaQuery(ClickHouse, "default", "", 20)
	if err != nil || !strings.Contains(q.SQL, "UNION DISTINCT") {
		t.Fatal("schema query must not depend on union_default_mode")
	}
}

func TestMySQLFamilyCompletionCannotCrossAuthorizedDatabase(t *testing.T) {
	for _, d := range []Dialect{MySQL, MariaDB, OceanBaseMySQL} {
		t.Run(string(d), func(t *testing.T) {
			r := &recordingConnector{}
			conn := catalogConnection(t, r)
			_, err := Metadata(context.Background(), conn, d, MetadataRequest{Context: Context{Database: "authorized", Schema: "other"}})
			if !errors.Is(err, ErrCatalogForbidden) || r.query != "" {
				t.Fatalf("cross database relations not rejected: %v", err)
			}
			_, err = Columns(context.Background(), conn, d, ColumnRequest{Context: Context{Database: "authorized"}, Schema: "other", Table: "t"})
			if !errors.Is(err, ErrCatalogForbidden) || r.query != "" {
				t.Fatalf("cross database columns not rejected: %v", err)
			}
		})
	}
}

func TestSQLServerUnqualifiedColumnsResolveDboFallback(t *testing.T) {
	conn, script := scriptedCatalogConn(t,
		catalogStep{rows: [][]driver.Value{{"db"}}},
		catalogStep{columns: 1},
		catalogStep{rows: [][]driver.Value{{"USERS"}}},
		catalogStep{rows: [][]driver.Value{{"db", "dbo", "USERS", "id", "int", false, "", int64(1)}}})
	columns, err := Columns(context.Background(), conn, SQLServer, ColumnRequest{Context: Context{Database: "db", Schema: "app"}, Table: "USERS"})
	if err != nil || len(columns) != 1 || columns[0].Name != "id" {
		t.Fatalf("unqualified dbo fallback failed: %+v %v", columns, err)
	}
	if len(script.calls) != 4 || !hasCatalogArgument(script.calls[1], "app") || !hasCatalogArgument(script.calls[2], "dbo") || !hasCatalogArgument(script.calls[3], "dbo") {
		t.Fatal("default schema search order changed")
	}
	conn, script = scriptedCatalogConn(t,
		catalogStep{rows: [][]driver.Value{{"db"}}},
		catalogStep{columns: 1})
	_, err = Columns(context.Background(), conn, SQLServer, ColumnRequest{Context: Context{Database: "db", Schema: "app"}, Schema: "app", Table: "USERS"})
	if !errors.Is(err, ErrCatalogNotFound) || len(script.calls) != 2 {
		t.Fatal("explicit schema unexpectedly fell back to dbo")
	}
}
