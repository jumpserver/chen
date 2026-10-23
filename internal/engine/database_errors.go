package engine

import (
	"errors"
	"fmt"

	dm "gitee.com/chunanyong/dm"
	"github.com/ClickHouse/clickhouse-go/v2"
	mssql "github.com/microsoft/go-mssqldb"
	ob "github.com/oceanbase/go-oceanbase-driver"
	oranetwork "github.com/sijms/go-ora/v2/network"
)

func extraDatabaseError(err error) *APIError {
	var ms mssql.Error
	if errors.As(err, &ms) {
		return &APIError{fmt.Sprintf("MSSQL_%d", ms.Number), ms.Message}
	}
	var oracle *oranetwork.OracleError
	if errors.As(err, &oracle) {
		return &APIError{fmt.Sprintf("ORA_%05d", oracle.ErrCode), oracle.Error()}
	}
	var oceanbase *ob.MySQLError
	if errors.As(err, &oceanbase) {
		return &APIError{fmt.Sprintf("OCEANBASE_%d", oceanbase.Number), oceanbase.Message}
	}
	var dameng *dm.DmError
	if errors.As(err, &dameng) {
		return &APIError{fmt.Sprintf("DM_%d", dameng.ErrCode), dameng.ErrText}
	}
	var ch *clickhouse.Exception
	if errors.As(err, &ch) {
		return &APIError{fmt.Sprintf("CLICKHOUSE_%d", ch.Code), ch.Message}
	}
	return db2DatabaseError(err)
}

func extraServerCancellation(err error) bool {
	var oracle *oranetwork.OracleError
	if errors.As(err, &oracle) {
		return oracle.ErrCode == 1013
	}
	var oceanbase *ob.MySQLError
	if errors.As(err, &oceanbase) {
		return oceanbase.Number == 1317
	}
	var ch *clickhouse.Exception
	if errors.As(err, &ch) {
		return ch.Code == 394
	}
	return false
}
