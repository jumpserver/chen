package engine

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"fmt"
	"testing"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

func TestOceanBaseOracleContextDoesNotUseMySQLFunctions(t *testing.T) {
	state := &executionDriverState{query: func(_ context.Context, query string) (driver.Rows, error) {
		switch query {
		case "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL":
			return &executionRows{columns: []string{"schema"}, types: []string{"VARCHAR2"}, values: [][]driver.Value{{"APP"}}}, nil
		case "SELECT USERENV('SID') FROM DUAL":
			return &executionRows{columns: []string{"sid"}, types: []string{"NUMBER"}, values: [][]driver.Value{{int64(42)}}}, nil
		default:
			return nil, fmt.Errorf("unexpected Oracle-tenant context SQL: %s", query)
		}
	}}
	db := sql.OpenDB(executionConnector{state})
	defer db.Close()
	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	current, sid, err := initializeConsole(context.Background(), conn, jumpserver.ConnectionConfig{Dialect: "oceanbase-oracle", Database: "APP"}, sqlservice.Context{})
	if err != nil || current.Database != "APP" || current.Schema != "APP" || sid != 42 {
		t.Fatalf("Oracle context failed: %+v sid=%d err=%v", current, sid, err)
	}
}
