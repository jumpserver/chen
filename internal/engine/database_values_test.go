package engine

import (
	"encoding/json"
	"github.com/shopspring/decimal"
	goora "github.com/sijms/go-ora/v2"
	"math/big"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAdditionalDriverValuesPreservePrecisionAndBinary(t *testing.T) {
	values := map[string]any{
		"decimal": decimal.RequireFromString("9007199254740993.123456789"),
		"wide":    new(big.Int).Lsh(big.NewInt(1), 100),
		"array":   []uint64{9007199254740993},
		"nested":  map[string]any{"number": int64(9007199254740993)},
	}
	encoded, err := json.Marshal(normalizeValue(values, ""))
	if err != nil {
		t.Fatal(err)
	}
	expected := `{"array":["9007199254740993"],"decimal":"9007199254740993.123456789","nested":{"number":"9007199254740993"},"wide":"1267650600228229401496703205376"}`
	if string(encoded) != expected {
		t.Fatalf("structured numeric result changed: %s", encoded)
	}
	for _, kind := range []string{"BLOB", "VARBINARY", "RAW", "IMAGE", "BYTEA"} {
		if got := normalizeValue([]byte("Hello"), kind); got != "0x48656c6c6f" {
			t.Fatalf("%s ASCII binary became text: %v", kind, got)
		}
	}
	for _, value := range []any{goora.Clob{String: "", Valid: true}, goora.NClob{String: "", Valid: true}} {
		got, err := materializeValue(value, "CLOB", 1024)
		if err != nil || got != "" {
			t.Fatal("valid empty LOB was changed to NULL")
		}
	}
	if got, err := materializeValue(goora.Clob{}, "CLOB", 1024); err != nil || got != nil {
		t.Fatal("NULL LOB became an empty string")
	}
}

func TestDb2ActiveCancellationIsNotFalselyAcknowledged(t *testing.T) {
	for _, status := range []string{"queued", "running"} {
		t.Run(status, func(t *testing.T) {
			v, s, _, e := executionFixture(t, "db2", "SELECT 1 FROM SYSIBM.SYSDUMMY1", &executionDriverState{})
			e.view.Status = status
			if s.view().CanCancel {
				t.Fatal("Db2 advertised active cancellation")
			}
			w := httptest.NewRecorder()
			v.Handler().ServeHTTP(w, engineRequest("POST", "/chen/api/v1/executions/"+e.view.ID+"/cancel", "", "token"))
			if status == "running" {
				if w.Code != 409 || e.cancelRequested.Load() || e.ctx.Err() != nil {
					t.Fatalf("running Db2 cancel misrepresented: code=%d", w.Code)
				}
			} else {
				if w.Code != 202 || e.ctx.Err() == nil {
					t.Fatalf("queued Db2 work was not withdrawn: code=%d", w.Code)
				}
				select {
				case <-e.cancelDone:
				case <-time.After(time.Second):
					t.Fatal("queued cancellation did not finish")
				}
			}
		})
	}
}
