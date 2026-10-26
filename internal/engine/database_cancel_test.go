package engine

import (
	"context"
	"errors"
	"fmt"
	"testing"

	mssql "github.com/microsoft/go-mssqldb"
)

func TestDriverCancellationRequiresSupportedAcknowledgement(t *testing.T) {
	for _, tt := range []struct {
		dialect string
		err     error
		want    bool
	}{
		{"sqlserver", context.Canceled, true},
		{"sqlserver", fmt.Errorf("query: %w", context.DeadlineExceeded), true},
		{"sqlserver", mssql.StreamError{InnerError: context.DeadlineExceeded}, false},
		{"sqlserver", errors.New("connection lost"), false},
		{"sqlserver", nil, false},
		{"mysql", context.Canceled, false},
		{"oracle", context.Canceled, false},
		{"dameng", context.Canceled, false},
		{"clickhouse", context.Canceled, false},
		{"db2", context.Canceled, false},
	} {
		if got := driverConfirmedCancellation(tt.dialect, tt.err); got != tt.want {
			t.Errorf("%s %T: confirmed=%v, want %v", tt.dialect, tt.err, got, tt.want)
		}
	}
}
