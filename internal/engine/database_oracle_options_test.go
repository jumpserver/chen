package engine

import (
	"github.com/jumpserver/chen/internal/jumpserver"
	goora "github.com/sijms/go-ora/v2"
	"github.com/sijms/go-ora/v2/configurations"
	"testing"
)

func TestOraclePrivilegeOnlyWhenRequested(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		options := oracleConnectionOptions(jumpserver.ConnectionConfig{OracleSYSDBA: enabled})
		c, err := configurations.ParseConfig(goora.BuildUrl("localhost", 1521, "service", "SYS", "synthetic", options))
		if err != nil {
			t.Fatal(err)
		}
		if (c.DBAPrivilege == configurations.SYSDBA) != enabled {
			t.Fatal("Oracle privilege differs from Core authorization")
		}
	}
}
