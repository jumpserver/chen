package engine

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

func TestDatabaseTLSModes(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	root := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test root"}, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	der, err := x509.CreateCertificate(rand.Reader, root, root, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	ca, _ := x509.ParseCertificate(der)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), DNSNames: []string{"db.test"}, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature}
	leafDER, err := x509.CreateCertificate(rand.Reader, leaf, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(leafDER)
	config := jumpserver.ConnectionConfig{Dialect: "postgresql", Host: "db.test", TLS: jumpserver.TLSConfig{Enabled: true, CACert: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), Mode: "verify-full"}}
	full, err := makeTLS(config)
	if err != nil {
		t.Fatal(err)
	}
	if full.InsecureSkipVerify || full.ServerName != "db.test" {
		t.Fatal("verify-full weakened")
	}
	if _, err = cert.Verify(x509.VerifyOptions{Roots: full.RootCAs, DNSName: "wrong.test"}); err == nil {
		t.Fatal("hostname mismatch accepted")
	}
	config.TLS.Mode = "verify-ca"
	config.Host = "wrong.test"
	chain, err := makeTLS(config)
	if err != nil {
		t.Fatal(err)
	}
	if chain.VerifyConnection == nil {
		t.Fatal("chain validation missing")
	}
	if err = chain.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}); err != nil {
		t.Fatal("verify-ca must ignore hostname", err)
	}
	if err = chain.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{root}}); err == nil {
		t.Fatal("untrusted certificate accepted")
	}
	config.TLS.InsecureSkipVerify = true
	if _, err = makeTLS(config); err == nil {
		t.Fatal("conflicting verification options accepted")
	}
	config.TLS.InsecureSkipVerify = false
	config.TLS.CACert = ""
	config.TLS.Mode = "require"
	required, err := makeTLS(config)
	if err != nil || required == nil || !required.InsecureSkipVerify {
		t.Fatal("require must enforce encryption")
	}
	config.TLS.Mode = "prefer"
	if _, err = makeTLS(config); err == nil {
		t.Fatal("unsupported downgrade mode accepted")
	}
	config.TLS.Mode = "disable"
	disabled, err := makeTLS(config)
	if err != nil || disabled != nil {
		t.Fatal("disable must disable TLS")
	}
}
func TestPostgresConfigHasNoImplicitService(t *testing.T) {
	t.Setenv("PGSERVICE", "")
	t.Setenv("PGHOST", "unexpected.invalid")
	t.Setenv("PGSSLMODE", "verify-full")
	t.Setenv("PGSSLROOTCERT", "/nonexistent")
	db, err := openDatabase(jumpserver.ConnectionConfig{Dialect: "postgresql", Host: "127.0.0.1", Port: 5432, Database: "chen", User: "user"}, 1)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	t.Setenv("PGSERVICE", "unexpected")
	if db, err = openDatabase(jumpserver.ConnectionConfig{Dialect: "postgresql"}, 1); err == nil {
		db.Close()
		t.Fatal("unexpected service configuration accepted")
	}
}
