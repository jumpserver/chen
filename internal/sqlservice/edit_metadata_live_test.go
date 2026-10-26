package sqlservice_test

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"net"
	"os"
	"strconv"
	"testing"
	"time"

	_ "gitee.com/chunanyong/dm"
	"github.com/go-sql-driver/mysql"
	"github.com/jumpserver/chen/internal/sqlservice"
	"github.com/jumpserver/chen/internal/standalone"
)

// Unlike the read-only catalog probe, this explicit write opt-in creates only a
// random owned table and removes it before returning. No application row is read.
func TestEditMetadataOceanBaseLive(t *testing.T) {
	path := os.Getenv("CHEN_WRITE_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_WRITE_BACKENDS to allow a disposable metadata table")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("read private fixture")
	}
	for _, b := range cfg.Backends {
		if b.Protocol != "oceanbase-mysql" {
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
			defer cancel()
			config := mysql.NewConfig()
			config.User, config.Passwd, config.DBName = b.User, b.Password, b.Database
			config.Net, config.Addr = "tcp", net.JoinHostPort(b.Host, strconv.Itoa(b.Port))
			config.Timeout, config.ReadTimeout, config.WriteTimeout = 10*time.Second, 30*time.Second, 30*time.Second
			db, err := sql.Open("mysql", config.FormatDSN())
			if err != nil {
				t.Fatal("open fixture")
			}
			defer db.Close()
			conn, err := db.Conn(ctx)
			if err != nil {
				t.Fatal("connect fixture")
			}
			defer conn.Close()
			random := make([]byte, 8)
			if _, err := rand.Read(random); err != nil {
				t.Fatal(err)
			}
			name := "CHEN_META_" + hex.EncodeToString(random)
			if _, err := conn.ExecContext(ctx, "CREATE TABLE `"+name+"` (`ID` BIGINT PRIMARY KEY, `TITLE` VARCHAR(120), `DERIVED` BIGINT GENERATED ALWAYS AS (`ID` + 1) VIRTUAL)"); err != nil {
				t.Fatal("create metadata fixture:", err)
			}
			defer func() {
				cleanup, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				defer cancel()
				if _, err := conn.ExecContext(cleanup, "DROP TABLE `"+name+"`"); err != nil {
					t.Error("cleanup owned metadata table:", err)
				}
			}()
			var lowerCase int
			var actual string
			if err := conn.QueryRowContext(ctx, "SELECT @@lower_case_table_names").Scan(&lowerCase); err != nil {
				t.Fatal(err)
			}
			if err := conn.QueryRowContext(ctx, "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND LOWER(TABLE_NAME)=LOWER(?)", b.Database, name).Scan(&actual); err != nil {
				t.Fatal(err)
			}
			t.Logf("lower_case_table_names=%d, stored name differs=%t", lowerCase, actual != name)
			info, err := sqlservice.LoadEditTable(ctx, conn, sqlservice.OceanBaseMySQL, sqlservice.Context{Database: b.Database}, b.Database, name)
			if err != nil {
				t.Fatal("load edit metadata:", err)
			}
			if len(info.PrimaryKey) != 1 || len(info.Columns) != 3 || !info.Columns[2].Generated {
				t.Fatalf("incomplete metadata: keys=%d columns=%+v", len(info.PrimaryKey), info.Columns)
			}
		})
	}
}

// This opt-in probe only reads existing catalog metadata, never application
// rows and never DDL/DML. Credentials remain in the private fixture file.
func TestEditMetadataDamengLive(t *testing.T) {
	path := os.Getenv("CHEN_EDIT_METADATA_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_EDIT_METADATA_BACKENDS to a private standalone config")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("read private fixture")
	}
	for _, b := range cfg.Backends {
		if b.Protocol != "dameng" {
			continue
		}
		t.Run(b.Selector, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
			defer cancel()
			dsn := "dm://" + b.User + ":" + b.Password + "@" + net.JoinHostPort(b.Host, strconv.Itoa(b.Port)) + "?connectTimeout=10000&socketTimeout=30&doSwitch=0&logLevel=off&lobMode=1"
			db, err := sql.Open("dm", dsn)
			if err != nil {
				t.Fatal("open fixture")
			}
			defer db.Close()
			conn, err := db.Conn(ctx)
			if err != nil {
				t.Fatal("connect fixture")
			}
			defer conn.Close()
			if b.Database == "" {
				if err := conn.QueryRowContext(ctx, "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL").Scan(&b.Database); err != nil {
					t.Fatal("resolve fixture database")
				}
			}
			schemas, err := sqlservice.SchemaRows(ctx, conn, sqlservice.Dameng, b.Database, "", 200)
			if err != nil {
				t.Fatal(err)
			}
			for _, schema := range schemas {
				objects, err := sqlservice.ObjectRows(ctx, conn, sqlservice.Dameng, sqlservice.CatalogObjectRequest{Database: b.Database, Schema: schema.Name, Kind: "table", Limit: 20})
				if err != nil {
					t.Fatal(err)
				}
				if len(objects) == 0 {
					continue
				}
				info, err := sqlservice.LoadEditTable(ctx, conn, sqlservice.Dameng, sqlservice.Context{Database: b.Database}, schema.Name, objects[0].Name)
				if err != nil {
					t.Fatal(err)
				}
				if len(info.Columns) == 0 {
					t.Fatal("no column metadata")
				}
				t.Logf("metadata read: %d columns, %d key columns", len(info.Columns), len(info.PrimaryKey))
				return
			}
			t.Fatal("fixture has no visible base table")
		})
	}
}
