package main

import (
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"time"
)

var version = "dev"

// Utility commands do not open the audit journal or construct a provider.
func runUtility(args []string, output io.Writer) (bool, error) {
	if len(args) == 0 {
		return false, nil
	}
	switch args[0] {
	case "version", "--version":
		if len(args) != 1 {
			return true, errors.New("version does not accept arguments")
		}
		wisp := "unknown"
		if info, ok := debug.ReadBuildInfo(); ok {
			for _, dep := range info.Deps {
				if dep.Path == "github.com/jumpserver/wisp" {
					wisp = dep.Version
				}
			}
		}
		_, err := fmt.Fprintf(output, "Chen %s %s/%s Wisp %s\n", version, runtime.GOOS, runtime.GOARCH, wisp)
		return true, err
	case "healthcheck":
		if len(args) != 1 {
			return true, errors.New("healthcheck does not accept arguments")
		}
		host, port, err := net.SplitHostPort(env("CHEN_HTTP_ADDR", "127.0.0.1:8083"))
		if err != nil {
			return true, errors.New("healthcheck_invalid_listen_address")
		}
		switch host {
		case "", "0.0.0.0":
			host = "127.0.0.1"
		case "::":
			host = "::1"
		}
		// Ignore ambient HTTP proxies and redirects when probing our own listener.
		transport := &http.Transport{Proxy: nil}
		defer transport.CloseIdleConnections()
		client := &http.Client{Transport: transport, Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		response, err := client.Get("http://" + net.JoinHostPort(host, port) + "/healthy")
		if err != nil {
			return true, errors.New("healthcheck_unreachable")
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return true, errors.New("healthcheck_unhealthy")
		}
		return true, nil
	default:
		return false, nil
	}
}
