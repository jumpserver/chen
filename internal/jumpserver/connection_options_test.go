package jumpserver

import (
	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"testing"
)

func TestAuthorizedConnectionOptions(t *testing.T) {
	token := validToken()
	token.ConnectOptions = &protobuf.ConnectOptions{Settings: map[string]string{"use_sysdba": "true"}}
	c := ConnectionConfig{Dialect: "oracle"}
	if err := applyPlatformConnectionOptions(&c, token); err != nil || !c.OracleSYSDBA {
		t.Fatal("Core SYSDBA option was lost")
	}
	token.ConnectOptions.Settings["use_sysdba"] = "invalid"
	if err := applyPlatformConnectionOptions(&c, token); err == nil {
		t.Fatal("invalid SYSDBA option accepted")
	}
	c = ConnectionConfig{Dialect: "sqlserver"}
	if err := applyPlatformConnectionOptions(&c, token); err != nil || !c.TLS.Enabled {
		t.Fatal("SQL Server encryption default was lost")
	}
	c = ConnectionConfig{Dialect: "sqlserver", TLS: TLSConfig{Enabled: true, ServerName: "db.example"}}
	if err := applyPlatformConnectionOptions(&c, token); err != nil || c.TLS.InsecureSkipVerify {
		t.Fatal("explicit certificate verification downgraded")
	}
}
