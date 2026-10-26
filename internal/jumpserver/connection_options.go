package jumpserver

import (
	"errors"
	"strconv"
	"strings"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func applyPlatformConnectionOptions(c *ConnectionConfig, data *protobuf.TokenAuthInfo) error {
	if c.Dialect == "oracle" {
		value := data.GetConnectOptions().GetSettings()["use_sysdba"]
		if value != "" {
			enabled, err := strconv.ParseBool(value)
			if err != nil {
				return errors.New("invalid_oracle_sysdba_option")
			}
			c.OracleSYSDBA = enabled
		}
	}
	if c.Dialect != "sqlserver" {
		return nil
	}
	encrypt := true
	for _, p := range data.GetPlatform().GetProtocols() {
		if p == nil || !strings.EqualFold(p.Name, "sqlserver") {
			continue
		}
		if v, ok := p.GetSettings()["encrypt"]; ok {
			var err error
			encrypt, err = strconv.ParseBool(v)
			if err != nil {
				return errors.New("invalid_sqlserver_encrypt_option")
			}
		}
	}
	if encrypt && !c.TLS.Enabled {
		// The platform's encrypt switch preserves the JDBC encrypted transport.
		// Asset UseSSL opts into certificate verification; it is never downgraded
		// by a platform setting, including encrypt=false.
		c.TLS.Enabled = true
		c.TLS.InsecureSkipVerify = true
	}
	return nil
}
