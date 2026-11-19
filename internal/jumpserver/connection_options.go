package jumpserver

import (
	"errors"
	"strconv"
	"strings"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
)

func applyPlatformConnectionOptions(c *ConnectionConfig, data *protobuf.TokenAuthInfo) error {
	if c.Dialect == "clickhouse" {
		for _, protocol := range data.GetPlatform().GetProtocols() {
			if protocol == nil || !strings.EqualFold(protocol.Name, "clickhouse") {
				continue
			}
			if value, configured := protocol.GetSettings()["transport"]; configured {
				transport := strings.ToLower(strings.TrimSpace(value))
				if transport != "http" && transport != "native" {
					return errors.New("invalid_clickhouse_transport_option")
				}
				c.Transport = transport
				return nil
			}
		}
		// Preserve the original asset protocol before a gateway replaces its
		// port. Custom ports may serve Java's HTTP or Go's Native connections;
		// only an explicit Native-port HTTP response may select Native there.
		switch c.Port {
		case 8123, 8443:
			c.Transport = "http"
		case 9000, 9440:
			c.Transport = "native"
		default:
			c.Transport = "auto"
		}
		return nil
	}
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
