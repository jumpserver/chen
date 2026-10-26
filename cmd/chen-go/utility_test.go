package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestUtilityHealthDoesNotFollowRedirectOrUseProxy(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusServiceUnavailable, http.StatusFound} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/healthy" {
					t.Errorf("unexpected path: %s", r.URL.Path)
				}
				w.Header().Set("Location", "http://127.0.0.1:1/should-not-be-contacted")
				w.WriteHeader(status)
			}))
			defer server.Close()
			t.Setenv("CHEN_HTTP_ADDR", strings.TrimPrefix(server.URL, "http://"))
			t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
			handled, err := runUtility([]string{"healthcheck"}, &bytes.Buffer{})
			if !handled || (err == nil) != (status == http.StatusOK) {
				t.Fatalf("handled=%v error=%v", handled, err)
			}
		})
	}
}
func TestUtilityVersionDoesNotInitializeProvider(t *testing.T) {
	t.Setenv("CHEN_STATE_DIR", "/does/not/exist")
	var out bytes.Buffer
	handled, err := runUtility([]string{"--version"}, &out)
	if !handled || err != nil || !strings.HasPrefix(out.String(), "Chen ") {
		t.Fatalf("handled=%v error=%v output=%q", handled, err, out.String())
	}
}
