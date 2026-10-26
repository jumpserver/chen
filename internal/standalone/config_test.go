package standalone

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testConfig() Config {
	return Config{Version: 1, Backends: []BackendConfig{{Selector: "local-mysql", Protocol: "mysql", Host: "127.0.0.1", Port: 3306, User: "local_operator", Password: "synthetic-db-password", Database: "app"}}}
}
func writeTestConfig(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "backends.yaml")
	if os.WriteFile(path, []byte(content), 0600) != nil {
		t.Fatal("write test config failed")
	}
	return path
}
func configJSON(t *testing.T, c Config) string {
	t.Helper() /* Config deliberately hides secrets from JSON; encode the YAML schema separately. */
	var backends []map[string]any
	for _, b := range c.Backends {
		backends = append(backends, map[string]any{"selector": b.Selector, "name": b.Name, "protocol": b.Protocol, "host": b.Host, "port": b.Port, "user": b.User, "password": b.Password, "database": b.Database, "use_ssl": b.UseSSL, "ca_cert": b.CACert, "client_cert": b.ClientCert, "client_key": b.ClientKey, "server_name": b.ServerName, "ssl_mode": b.SSLMode, "allow_invalid_cert": b.AllowInvalidCert})
	}
	data, err := json.Marshal(map[string]any{"version": c.Version, "backends": backends})
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestLoadNormalizesAliasesAndKeepsGloballyUniqueSelectors(t *testing.T) {
	for _, protocol := range []string{"mysql", "mariadb", "postgresql", "postgres", "pgsql", "pg"} {
		t.Run(protocol, func(t *testing.T) {
			cfg := testConfig()
			cfg.Backends[0].Protocol = protocol
			loaded, err := Load(writeTestConfig(t, configJSON(t, cfg)), LoadOptions{})
			if err != nil {
				t.Fatal(err)
			}
			want := "postgresql"
			if protocol == "mysql" || protocol == "mariadb" {
				want = protocol
			}
			if loaded.Backends[0].Protocol != want || loaded.Backends[0].Name != "local-mysql" {
				t.Fatal("protocol/name defaults were not normalized")
			}
		})
	}
	cfg := testConfig()
	second := cfg.Backends[0]
	second.Protocol = "postgresql"
	second.Port = 5432
	cfg.Backends = append(cfg.Backends, second)
	if _, err := Load(writeTestConfig(t, configJSON(t, cfg)), LoadOptions{}); err == nil || !strings.Contains(err.Error(), "globally unique") {
		t.Fatal("same selector across two protocols was accepted")
	}
}

func TestLoadRejectsUntrustedConfigurationShapeWithoutLeakingValues(t *testing.T) {
	base := configJSON(t, testConfig())
	for _, tc := range []struct{ name, content string }{
		{"unknown default", strings.Replace(base, `"selector":"local-mysql"`, `"selector":"local-mysql","default":true`, 1)},
		{"unknown access secret", strings.Replace(base, `"selector":"local-mysql"`, `"selector":"local-mysql","access_secret":"synthetic-db-password"`, 1)},
		{"duplicate key", "version: 1\nversion: 1\nbackends: []\n"},
		{"wrong typed secret", strings.Replace(base, `"port":3306`, `"port":"synthetic-db-password"`, 1)},
		{"multiple documents", base + "\n---\nversion: 1\nbackends: []\n"},
		{"empty", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(writeTestConfig(t, tc.content), LoadOptions{})
			if err == nil {
				t.Fatal("invalid config was accepted")
			}
			if strings.Contains(err.Error(), "synthetic-db-password") {
				t.Fatal("invalid YAML leaked a credential")
			}
		})
	}
}

func TestLoadChecksPermissionsSymlinksAndMaximumFileSize(t *testing.T) {
	path := writeTestConfig(t, configJSON(t, testConfig()))
	if err := os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path, LoadOptions{}); err == nil {
		t.Fatal("world-readable credential file was accepted")
	}
	if _, err := Load(path, LoadOptions{AllowInsecurePermissions: true}); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(t.TempDir(), "link.yaml")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(link, LoadOptions{AllowInsecurePermissions: true}); err == nil {
		t.Fatal("symlink credential file was accepted")
	}
	oversized := writeTestConfig(t, strings.Repeat("x", maxConfigFileSize+1))
	if _, err := Load(oversized, LoadOptions{}); err == nil {
		t.Fatal("oversized config was accepted")
	}
	if _, err := Load(t.TempDir(), LoadOptions{}); err == nil {
		t.Fatal("directory was accepted as config")
	}
}

func TestValidationRejectsUnsupportedOrAmbiguousConnectionSettings(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*BackendConfig)
	}{
		{"selector space", func(b *BackendConfig) { b.Selector = "invalid selector" }},
		{"selector URL", func(b *BackendConfig) { b.Selector = "mysql://local" }},
		{"missing selector", func(b *BackendConfig) { b.Selector = "" }},
		{"protocol", func(b *BackendConfig) { b.Protocol = "redis" }},
		{"host URL", func(b *BackendConfig) { b.Host = "mysql://localhost" }},
		{"host port", func(b *BackendConfig) { b.Host = "localhost:3306" }},
		{"host control", func(b *BackendConfig) { b.Host = "localhost\x07" }},
		{"port", func(b *BackendConfig) { b.Port = 65536 }},
		{"user", func(b *BackendConfig) { b.User = "" }},
		{"half key pair", func(b *BackendConfig) { b.UseSSL = true; b.ClientKey = "synthetic-private-key" }},
		{"CA without TLS", func(b *BackendConfig) { b.CACert = "synthetic-ca" }},
		{"invalid CA", func(b *BackendConfig) { b.UseSSL = true; b.CACert = "synthetic-ca" }},
		{"MySQL sslmode", func(b *BackendConfig) { b.UseSSL = true; b.SSLMode = "require" }},
		{"PG fallback", func(b *BackendConfig) { b.Protocol = "postgresql"; b.UseSSL = true; b.SSLMode = "prefer" }},
		{"conflicting verify", func(b *BackendConfig) {
			b.Protocol = "postgresql"
			b.UseSSL = true
			b.SSLMode = "verify-full"
			b.AllowInvalidCert = true
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testConfig()
			tc.mutate(&cfg.Backends[0])
			if _, err := normalize(cfg); err == nil {
				t.Fatal("invalid connection config was accepted")
			}
		})
	}
}

func TestTLSPEMIPv6AndAccidentalFormattingRemainSafe(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test.example.invalid"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	pemCert := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	derKey, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pemKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: derKey}))
	cfg := testConfig()
	b := &cfg.Backends[0]
	b.Protocol = "postgresql"
	b.Host = "[::1]"
	b.UseSSL = true
	b.CACert = pemCert
	b.ClientCert = pemCert
	b.ClientKey = pemKey
	b.SSLMode = "verify-full"
	b.ServerName = "test.example.invalid"
	loaded, err := Load(writeTestConfig(t, configJSON(t, cfg)), LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Backends[0].Host != "::1" {
		t.Fatal("IPv6 was not normalized")
	}
	data, _ := json.Marshal(loaded)
	backendData, _ := json.Marshal(loaded.Backends[0])
	formatted := fmt.Sprintf("%+v %#v %+v %#v", loaded, loaded, loaded.Backends[0], loaded.Backends[0])
	for _, secret := range []string{"synthetic-db-password", pemKey, "local_operator"} {
		if strings.Contains(string(data)+string(backendData)+formatted, secret) {
			t.Fatal("formatting or JSON leaked configured credentials")
		}
	}
}

func TestExampleConfigurationLoads(t *testing.T) {
	data, err := os.ReadFile("../../conf/standalone_backends_example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(writeTestConfig(t, string(data)), LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Backends) != 2 || cfg.Backends[0].Selector != "local-mysql" || cfg.Backends[1].Selector != "local-pg" {
		t.Fatal("example selectors do not match standalone URLs")
	}
}
