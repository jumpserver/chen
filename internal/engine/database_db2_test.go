//go:build ibmdb

package engine

import (
	"database/sql"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/standalone"
)

func readonlyDb2Fixture(t *testing.T, timeout int) (*sql.DB, func(error) string) {
	t.Helper()
	path := os.Getenv("CHEN_READONLY_BACKENDS")
	if path == "" || os.Getenv("CHEN_READONLY_SELECTOR") != "qa-db2" {
		t.Skip("explicit Db2 test fixture not selected")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("invalid private configuration")
	}
	for _, b := range cfg.Backends {
		if b.Selector != "qa-db2" {
			continue
		}
		dsn, err := db2DSN(jumpserver.ConnectionConfig{Host: b.Host, Port: b.Port, User: b.User, Password: b.Password, Database: b.Database}, timeout)
		if err != nil {
			t.Fatal("invalid Db2 settings")
		}
		db, err := sql.Open("go_ibm_db", dsn)
		if err != nil {
			t.Fatal("cannot construct Db2 connection")
		}
		t.Cleanup(func() { db.Close() })
		if err = db.Ping(); err != nil {
			t.Fatal("cannot connect to Db2 fixture")
		}
		return db, func(err error) string {
			message := err.Error()
			for _, secret := range []string{b.Password, b.Host, b.User, b.Database} {
				if secret != "" {
					message = strings.ReplaceAll(message, secret, "[redacted]")
				}
			}
			return message
		}
	}
	t.Fatal("selected Db2 fixture missing")
	return nil, nil
}

// No application data is accessed: the timed query generates integers in a CTE.
func TestDb2CLIQueryTimeout(t *testing.T) {
	db, _ := readonlyDb2Fixture(t, 1)
	started := time.Now()
	var sum int64
	err := db.QueryRow("WITH counter(n) AS (VALUES 1 UNION ALL SELECT n+1 FROM counter WHERE n<100000000) SELECT SUM(BIGINT(n)) FROM counter").Scan(&sum)
	elapsed := time.Since(started)
	if err == nil {
		t.Fatal("synthetic query completed without exercising CLI timeout")
	}
	if !strings.Contains(err.Error(), "57014") || elapsed > 10*time.Second {
		t.Fatalf("CLI timeout was not enforced: elapsed=%s SQLSTATE57014=%v", elapsed, strings.Contains(err.Error(), "57014"))
	}
	t.Logf("CLI QueryTimeout=1 returned SQLSTATE 57014 in %s", elapsed.Round(time.Millisecond))
	if err = db.QueryRow("VALUES 1").Scan(&sum); err != nil || sum != 1 {
		t.Fatal("connection not reusable after CLI timeout")
	}
}

func TestDb2WideValues(t *testing.T) {
	db, safe := readonlyDb2Fixture(t, 60)
	var national, binary, precise []byte
	err := db.QueryRow("SELECT CAST('中文😀' AS DBCLOB(1M)), BLOB('Hello'), CAST('9007199254740993.123456789' AS DECIMAL(31,9)) FROM SYSIBM.SYSDUMMY1").Scan(&national, &binary, &precise)
	if err != nil {
		t.Fatal(safe(err))
	}
	if string(national) != "中文😀" {
		t.Fatalf("DBCLOB Unicode conversion changed: %x", national)
	}
	if string(binary) != "Hello" {
		t.Fatal("ASCII BLOB changed")
	}
	if string(precise) != "9007199254740993.123456789" {
		t.Fatal("DECIMAL precision changed")
	}
	var wide, empty []byte
	err = db.QueryRow("SELECT REPEAT(CAST('中文😀' AS DBCLOB(3M)),150000)||REPEAT(CAST('中文😀' AS DBCLOB(3M)),150000), CAST('' AS DBCLOB(1M)) FROM SYSIBM.SYSDUMMY1").Scan(&wide, &empty)
	if err != nil {
		t.Fatal(safe(err))
	}
	if string(wide) != strings.Repeat("中文😀", 300000) {
		t.Fatalf("large DBCLOB changed: got %d bytes", len(wide))
	}
	if empty == nil || len(empty) != 0 {
		t.Fatal("empty DBCLOB became NULL")
	}
}
