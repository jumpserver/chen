package sqlservice

import (
	"context"
	"database/sql"
	"os"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/standalone"
	goora "github.com/sijms/go-ora/v2"
)

// Opt-in metadata-only probe. No application rows or credentials are printed.
func TestOracleReadonlyCatalogMetadata(t *testing.T) {
	path := os.Getenv("CHEN_ORACLE_CATALOG_CONFIG")
	if path == "" {
		t.Skip("set CHEN_ORACLE_CATALOG_CONFIG to a private standalone YAML file")
	}
	config, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("could not load private catalog test configuration")
	}
	for _, backend := range config.Backends {
		if backend.Selector != "qa-oracle" || backend.Protocol != "oracle" {
			continue
		}
		if backend.UseSSL {
			t.Skip("use the engine database matrix for certificate-configured Oracle connections")
		}
		connector := goora.NewConnector(goora.BuildUrl(backend.Host, backend.Port, backend.Database, backend.User, backend.Password, map[string]string{"CONNECTION TIMEOUT": "10", "TIMEOUT": "15"}))
		db := sql.OpenDB(connector)
		defer db.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		conn, err := db.Conn(ctx)
		if err != nil {
			t.Fatal("catalog test connection failed")
		}
		defer conn.Close()
		started := time.Now()
		var count int
		if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'N'").Scan(&count); err != nil {
			t.Fatal("Oracle maintained-account metadata unavailable:", err)
		}
		t.Logf("ALL_USERS.ORACLE_MAINTAINED available, application owners=%d, elapsed=%s", count, time.Since(started))
		started = time.Now()
		schemas, err := SchemaRows(ctx, conn, Oracle, backend.Database, "", 200)
		if err != nil {
			t.Fatal("schema query failed:", err)
		}
		t.Logf("visible schemas=%d, elapsed=%s", len(schemas), time.Since(started))
		return
	}
	t.Fatal("qa-oracle configuration was not found")
}
