package sqlservice

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

// This fixture creates only its own disposable container. It never uses an
// existing server or the user's configured credentials.
func TestIntegrationPostgresInspectionColumnPermissions(t *testing.T) {
	if os.Getenv("CHEN_INTEGRATION") != "1" {
		t.Skip("set CHEN_INTEGRATION=1 for disposable PostgreSQL")
	}
	random := make([]byte, 16)
	if _, err := rand.Read(random); err != nil {
		t.Fatal(err)
	}
	key := hex.EncodeToString(random)
	name := "chen-inspection-" + key[:12]
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "docker", "run", "--rm", "-d", "--name", name, "-p", "127.0.0.1::5432", "-e", "POSTGRES_PASSWORD", "postgres:16-alpine")
	cmd.Env = append(os.Environ(), "POSTGRES_PASSWORD="+key)
	if err := cmd.Run(); err != nil {
		t.Fatal("start disposable PostgreSQL:", err)
	}
	t.Cleanup(func() {
		if err := exec.Command("docker", "rm", "-f", "-v", name).Run(); err != nil {
			t.Error("remove own PostgreSQL fixture:", err)
		}
	})
	port, err := exec.CommandContext(ctx, "docker", "port", name, "5432/tcp").Output()
	if err != nil {
		t.Fatal(err)
	}
	host, mapped, err := net.SplitHostPort(strings.TrimSpace(string(port)))
	if err != nil {
		t.Fatal(err)
	}
	connect := func(user string) *sql.DB {
		db, err := sql.Open("pgx", fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=postgres sslmode=disable", host, mapped, user, key))
		if err != nil {
			t.Fatal("open isolated database:", err)
		}
		t.Cleanup(func() { db.Close() })
		return db
	}
	admin := connect("postgres")
	deadline := time.Now().Add(30 * time.Second)
	for admin.PingContext(ctx) != nil {
		if time.Now().After(deadline) {
			t.Fatal("isolated PostgreSQL did not start")
		}
		time.Sleep(100 * time.Millisecond)
	}
	setup := []string{
		"CREATE ROLE column_reader LOGIN PASSWORD '" + key + "'",
		"CREATE TABLE inspection_parent(id int, secret int, PRIMARY KEY(id,secret))",
		"CREATE TABLE inspection_scoped(id int NOT NULL DEFAULT 11, secret int NOT NULL DEFAULT 97, visible_generated int GENERATED ALWAYS AS (secret+1) STORED, PRIMARY KEY(id,secret), FOREIGN KEY(id,secret) REFERENCES inspection_parent(id,secret))",
		"CREATE INDEX visible_index ON inspection_scoped(id)",
		"CREATE INDEX hidden_index ON inspection_scoped(secret)",
		"CREATE INDEX expression_index ON inspection_scoped((id+1))",
		"CREATE INDEX predicate_index ON inspection_scoped(id) WHERE secret>0",
		"CREATE INDEX include_index ON inspection_scoped(id) INCLUDE(secret)",
		"CREATE VIEW inspection_view AS SELECT id,secret FROM inspection_scoped",
		"GRANT SELECT(id,visible_generated) ON inspection_scoped TO column_reader",
		"GRANT SELECT(id) ON inspection_view TO column_reader",
	}
	for _, statement := range setup {
		if _, err := admin.ExecContext(ctx, statement); err != nil {
			t.Fatal("create metadata fixture:", err)
		}
	}
	scoped := connect("column_reader")
	conn, err := scoped.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	request := CatalogColumnRequest{Database: "postgres", Schema: "public", Table: "inspection_scoped"}
	info, err := InspectTable(ctx, conn, PostgreSQL, request)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Columns) != 2 {
		t.Fatal("column-scoped metadata changed visibility")
	}
	encoded, _ := json.Marshal(info)
	if strings.Contains(string(encoded), "secret") {
		t.Fatal("restricted column name or expression leaked")
	}
	if len(info.Sections["primary_key"].Items) != 0 || len(info.Sections["foreign_keys"].Items) != 0 {
		t.Fatal("partial composite constraint leaked")
	}
	indexes := info.Sections["indexes"]
	if indexes.Error != "" || len(indexes.Items) != 1 || indexes.Items[0]["name"] != "visible_index" {
		t.Fatalf("unexpected visible indexes: %+v", indexes)
	}
	defaults := info.Sections["defaults"]
	if defaults.Error != "" {
		query, _, _ := inspectionQuery(PostgreSQL, request, "defaults")
		rows, queryErr := conn.QueryContext(ctx, query.SQL, query.Args...)
		if rows != nil {
			rows.Close()
		}
		t.Fatalf("fixture default query error: %v", queryErr)
	}
	if defaults.Error != "" || len(defaults.Items) != 1 || defaults.Items[0]["column"] != "id" {
		t.Fatalf("unexpected visible defaults: %+v", defaults)
	}
	ddl, err := TableDefinition(ctx, conn, PostgreSQL, request)
	if err != nil || ddl.Supported || ddl.Text != "" {
		t.Fatal("column grant received a table definition", err)
	}
	ownerConn, err := admin.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer ownerConn.Close()
	request.Table = "inspection_view"
	ddl, err = TableDefinition(ctx, ownerConn, PostgreSQL, request)
	if err != nil || ddl.Error != "" || ddl.Source != "native_view_query" || !strings.Contains(ddl.Text, "SELECT") || strings.Contains(ddl.Text, "CREATE TABLE") {
		t.Fatalf("invalid view definition: %+v error=%v", ddl, err)
	}
	ddl, err = TableDefinition(ctx, conn, PostgreSQL, request)
	if err != nil || ddl.Supported || ddl.Text != "" {
		t.Fatal("column grant received hidden view definition", err)
	}
	t.Log("verified restricted columns/defaults/composite keys/indexes and native view definition against disposable PostgreSQL")
}
