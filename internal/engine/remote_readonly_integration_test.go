package engine

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/jumpserver/chen/internal/sqlservice"
	"github.com/jumpserver/chen/internal/standalone"
)

// This opt-in suite never creates objects or reads application rows. It uses
// existing visible catalog objects for completion and a WHERE 1=0 identifier
// check; every returned value is a synthetic SELECT constant. Credentials stay
// in a private standalone YAML file, never command arguments or test logs.
func TestReadonlyDatabaseMatrix(t *testing.T) {
	path := os.Getenv("CHEN_READONLY_BACKENDS")
	if path == "" {
		t.Skip("set CHEN_READONLY_BACKENDS to a private standalone YAML file")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("read private database configuration:", err)
	}
	selected := os.Getenv("CHEN_READONLY_SELECTOR")
	for _, backend := range cfg.Backends {
		if selected != "" && backend.Selector != selected {
			continue
		}
		t.Run(backend.Selector, func(t *testing.T) {
			auditDir := filepath.Join(t.TempDir(), "audit")
			provider := singleIntegrationProvider(t, path, auditDir)
			server := New(provider, Options{SingleMode: true, StateDir: t.TempDir(), QueryTimeout: 30 * time.Second})
			httpServer := httptest.NewServer(server.Handler())
			defer httpServer.Close()
			defer server.Close()
			h := &integrationHTTP{t: t, url: httpServer.URL, client: &http.Client{Timeout: 35 * time.Second}}
			owner := singleIntegrationSession(h, backend.Selector, "readonly-matrix-"+integrationKey())
			console := h.console(owner, backend.Database)
			base := "/sessions/" + owner.Session.ID + "/catalog"
			var schemas struct {
				Items []sqlservice.CatalogSchema `json:"items"`
			}
			h.request("GET", base+"/schemas?limit=200", owner.Token, nil, 200, &schemas)
			var chosen *sqlservice.CatalogObject
			var columns []sqlservice.CatalogColumn
			for _, schema := range schemas.Items {
				var objects struct {
					Items []sqlservice.CatalogObject `json:"items"`
				}
				h.request("GET", base+"/objects?kind=table&limit=20&schema="+url.QueryEscape(schema.Name), owner.Token, nil, 200, &objects)
				for _, object := range objects.Items {
					var result struct {
						Items []sqlservice.CatalogColumn `json:"items"`
					}
					h.request("GET", base+"/columns?schema="+url.QueryEscape(object.Schema)+"&table="+url.QueryEscape(object.Name), owner.Token, nil, 200, &result)
					if len(result.Items) > 0 {
						chosen = &object
						columns = result.Items
						break
					}
				}
				if chosen != nil {
					break
				}
			}
			if chosen == nil {
				t.Fatal("no visible existing table with columns; catalog/completion cannot be accepted")
			}
			quote := func(name string) string { return readonlyMatrixQuote(backend.Protocol, name) }
			relation := quote(chosen.Schema) + "." + quote(chosen.Name)
			complete := func(marked string) sqlservice.CompletionResponse {
				at := strings.Index(marked, "|")
				if at < 0 {
					t.Fatal("missing completion cursor")
				}
				doc := marked[:at] + marked[at+1:]
				var response sqlservice.CompletionResponse
				h.request("POST", "/consoles/"+console.ID+"/completion", owner.Token, sqlservice.CompletionRequest{Document: doc, UTF16Offset: len(utf16.Encode([]rune(doc[:at]))), Version: 73}, 200, &response)
				if response.Version != 73 {
					t.Fatal("completion document version changed")
				}
				return response
			}
			contains := func(response sqlservice.CompletionResponse, name string) bool {
				for _, item := range response.Items {
					if item.Label == name {
						return true
					}
				}
				return false
			}
			tableCompletion := complete("SELECT * FROM " + quote(chosen.Schema) + "." + quote(chosen.Name) + "|")
			if !contains(tableCompletion, chosen.Name) {
				t.Fatal("schema-qualified table completion omitted existing table")
			}
			for _, query := range []string{"SELECT a.| FROM " + relation + " a", "SELECT b.| FROM " + relation + " a JOIN " + relation + " b ON a." + quote(columns[0].Name) + "=b." + quote(columns[0].Name)} {
				response := complete(query)
				for i, column := range columns {
					if i >= 10 {
						break
					}
					if !contains(response, column.Name) {
						t.Fatal("alias completion omitted visible column")
					}
				}
			}
			join := complete("SELECT * FROM " + relation + " a JOIN " + relation + " b ON |")
			aliasA, aliasB := "a", "b"
			switch backend.Protocol {
			case "oracle", "dameng", "db2", "oceanbase-oracle":
				aliasA, aliasB = "A", "B"
			}
			if !contains(join, aliasA+"."+columns[0].Name) || !contains(join, aliasB+"."+columns[0].Name) {
				t.Fatal("JOIN completion did not disambiguate both aliases")
			}
			empty := h.query(owner, console, "SELECT "+quote(columns[0].Name)+" FROM "+relation+" WHERE 1=0")
			if len(empty.Rows) != 0 {
				t.Fatal("zero-row identifier check returned rows")
			}
			query, expected := readonlyMatrixValueSQL(backend.Protocol)
			values := h.query(owner, console, query)
			if len(values.Rows) != 1 || len(values.Rows[0]) < 6 {
				t.Fatal("synthetic value query returned unexpected shape")
			}
			row := values.Rows[0]
			if row[0] != expected {
				t.Fatalf("large text changed or was truncated: expected %d bytes", len(expected))
			}
			if fmt.Sprint(row[1]) != "9007199254740993" {
				t.Fatal("big integer precision was lost")
			}
			if row[2] != `{"id":9007199254740993,"name":"示例","ok":true}` {
				t.Fatal("JSON text precision/Unicode changed")
			}
			if row[3] != nil {
				t.Fatal("NULL became a string")
			}
			if backend.Protocol != "oracle" && backend.Protocol != "oceanbase-oracle" && backend.Protocol != "dameng" && row[4] != "" {
				t.Fatal("empty string became NULL")
			}
			if backend.Protocol != "clickhouse" && row[5] != "0x48656c6c6f000aff" {
				t.Fatal("binary value changed or was not represented as hex")
			}
			if backend.Protocol == "clickhouse" && row[5] != `[`+`"9007199254740993"`+`]` {
				// Arrays remain structured JSON values in the HTTP contract.
				list, ok := row[5].([]any)
				if !ok || len(list) != 1 || list[0] != "9007199254740993" {
					t.Fatal("array integer precision was lost")
				}
			}
			t.Log("verified: connect, schemas/tables/columns, table/alias/JOIN completion, quoted zero-row SELECT, large Unicode text, precise bigint/JSON, NULL/empty, durable audit")
		})
	}
}

func readonlyMatrixQuote(d, name string) string {
	switch d {
	case "mysql", "mariadb", "oceanbase-mysql", "clickhouse":
		return "`" + strings.ReplaceAll(name, "`", "``") + "`"
	case "sqlserver":
		return "[" + strings.ReplaceAll(name, "]", "]]") + "]"
	default:
		return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
	}
}

func readonlyMatrixValueSQL(d string) (string, string) {
	jsonValue := `'{"id":9007199254740993,"name":"示例","ok":true}'`
	text := strings.Repeat("中文😀\n", 30000) + "END_OF_VALUE"
	piece := "'中文😀\n'"
	large := "CONCAT(REPEAT(" + piece + ",30000),'END_OF_VALUE')"
	integer := "CAST('9007199254740993' AS BIGINT)"
	binary := "X'48656c6c6f000aff'"
	tail := ""
	switch d {
	case "mysql", "mariadb", "oceanbase-mysql":
		integer = "CAST('9007199254740993' AS SIGNED)"
	case "postgresql":
		large = "repeat(" + piece + ",30000)||'END_OF_VALUE'"
		binary = "decode('48656c6c6f000aff','hex')"
	case "sqlserver":
		large = "REPLICATE(CAST(N" + piece + " AS NVARCHAR(MAX)),30000)+N'END_OF_VALUE'"
		jsonValue = "N" + jsonValue
		binary = "CAST(0x48656c6c6f000aff AS VARBINARY(MAX))"
	case "clickhouse":
		integer = "toUInt64('9007199254740993')"
		binary = "[toUInt64('9007199254740993')]"
	case "db2":
		large = "REPEAT(CAST(" + piece + " AS CLOB(1M)),30000)||'END_OF_VALUE'"
		tail = " FROM SYSIBM.SYSDUMMY1"
		binary = "BLOB(X'48656c6c6f000aff')"
	case "oracle", "oceanbase-oracle", "dameng":
		// A CLOB from the first operand avoids VARCHAR2 concatenation limits.
		// Use bounded chunks and no package calls or application tables.
		large = "TO_CLOB('')"
		for i := 0; i < 40; i++ {
			large += "||TO_CLOB(RPAD('x',4000,'x'))"
		}
		large += "||'中文😀END_OF_VALUE'"
		text = strings.Repeat("x", 160000) + "中文😀END_OF_VALUE"
		integer = "CAST('9007199254740993' AS NUMBER(19,0))"
		binary = "HEXTORAW('48656c6c6f000aff')"
		tail = " FROM DUAL"
	}
	return "SELECT " + large + " AS large_text, " + integer + " AS precise_integer, " + jsonValue + " AS json_text, NULL AS null_value, '' AS empty_value, " + binary + " AS binary_value" + tail, text
}
