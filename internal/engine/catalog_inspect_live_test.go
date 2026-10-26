package engine

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/sqlservice"
	"github.com/jumpserver/chen/internal/standalone"
)

func TestReadonlyMetadataInspection(t *testing.T) {
	path := os.Getenv("CHEN_READONLY_BACKENDS")
	if path == "" {
		t.Skip("private readonly backends configuration required")
	}
	cfg, err := standalone.Load(path, standalone.LoadOptions{})
	if err != nil {
		t.Fatal("private configuration could not be loaded")
	}
	for _, backend := range cfg.Backends {
		if selected := os.Getenv("CHEN_READONLY_SELECTOR"); selected != "" && selected != backend.Selector {
			continue
		}
		t.Run(backend.Selector, func(t *testing.T) {
			p := singleIntegrationProvider(t, path, filepath.Join(t.TempDir(), "audit"))
			v := New(p, Options{SingleMode: true, StateDir: t.TempDir()})
			server := httptest.NewServer(v.Handler())
			defer server.Close()
			defer v.Close()
			h := &integrationHTTP{t: t, url: server.URL, client: &http.Client{Timeout: 40 * time.Second}}
			owner := singleIntegrationSession(h, backend.Selector, "metadata-inspection-"+integrationKey())
			base := "/sessions/" + owner.Session.ID + "/catalog"
			var schemas struct {
				Items []sqlservice.CatalogSchema `json:"items"`
			}
			h.request("GET", base+"/schemas", owner.Token, nil, 200, &schemas)
			for _, schema := range schemas.Items {
				var objects struct {
					Items []sqlservice.CatalogObject `json:"items"`
				}
				h.request("GET", base+"/objects?kind=table&limit=1&schema="+url.QueryEscape(schema.Name), owner.Token, nil, 200, &objects)
				if len(objects.Items) == 0 {
					continue
				}
				o := objects.Items[0]
				var info sqlservice.TableInspection
				h.request("GET", base+"/inspect?schema="+url.QueryEscape(o.Schema)+"&table="+url.QueryEscape(o.Name), owner.Token, nil, 200, &info)
				if info.Sections["ddl"].Supported {
					var ddl sqlservice.MetadataSection
					h.request("GET", base+"/ddl?schema="+url.QueryEscape(o.Schema)+"&table="+url.QueryEscape(o.Name), owner.Token, nil, 200, &ddl)
					info.Sections["ddl"] = ddl
				}
				if len(info.Columns) == 0 {
					t.Fatal("inspection has no columns")
				}
				for name, section := range info.Sections {
					if section.Supported && section.Error != "" {
						t.Errorf("%s: %s", name, section.Error)
					}
					t.Logf("%s supported=%v entries=%d ddl_bytes=%d source=%s", name, section.Supported, len(section.Items), len(section.Text), section.Source)
				}
				return
			}
			t.Fatal("no visible table available")
		})
	}
}
