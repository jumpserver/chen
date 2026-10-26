//go:build !ibmdb

package engine

import (
	"crypto/tls"
	"database/sql"
	"errors"
	"github.com/jumpserver/chen/internal/jumpserver"
)

const db2Available = false

func openDB2(_ jumpserver.ConnectionConfig, _ *tls.Config) (*sql.DB, error) {
	return nil, errors.New("Db2 driver is not installed; build Chen with -tags ibmdb and IBM CLI")
}

func db2DatabaseError(err error) *APIError { return nil }
