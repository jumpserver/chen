//go:build ibmdb

package engine

import (
	"crypto/tls"
	"database/sql"
	"errors"
	"fmt"
	"strings"

	ibmdb "github.com/ibmdb/go_ibm_db"
	"github.com/jumpserver/chen/internal/jumpserver"
)

const db2Available = true

func openDB2(c jumpserver.ConnectionConfig, t *tls.Config) (*sql.DB, error) {
	if t != nil {
		return nil, errors.New("Db2 TLS requires IBM CLI certificate configuration; TLS cannot be silently disabled")
	}
	// The native CLI enforces an upper execution bound even though this driver
	// cannot interrupt SQLExecute when a Go context is canceled.
	dsn, err := db2DSN(c, 60)
	if err != nil {
		return nil, err
	}
	return sql.Open("go_ibm_db", dsn)
}

func db2DSN(c jumpserver.ConnectionConfig, queryTimeout int) (string, error) {
	// IBM CLI does not strip ODBC braces from HOSTNAME/UID. Reject DSN
	// separators instead of allowing configured credentials to inject options.
	for _, field := range []string{c.Host, c.Database, c.User, c.Password} {
		if strings.ContainsAny(field, ";\x00") {
			return "", errors.New("Db2 connection fields containing semicolon are unsupported by IBM CLI")
		}
	}
	return fmt.Sprintf("HOSTNAME=%s;PORT=%d;DATABASE=%s;UID=%s;PWD=%s;PROTOCOL=TCPIP;CONNECTTIMEOUT=10;QueryTimeout=%d", c.Host, c.Port, c.Database, c.User, c.Password, queryTimeout), nil
}

func db2DatabaseError(err error) *APIError {
	var dbError *ibmdb.Error
	if errors.As(err, &dbError) && (strings.Contains(dbError.APIName, "Exec") || strings.Contains(dbError.APIName, "Fetch") || strings.Contains(dbError.APIName, "Prepare")) {
		return &APIError{"DB2_SQL_ERROR", dbError.Error()}
	}
	return nil
}
