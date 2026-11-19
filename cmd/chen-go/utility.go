package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
	pb "github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
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
	case "wisp-healthcheck":
		o, err := parseOptions(args[1:])
		if err != nil {
			return true, err
		}
		transport, err := wispCredentials(o.wisp, o.ca, o.serverName, o.plaintext)
		if err != nil {
			return true, err
		}
		client, err := grpc.NewClient(o.wisp, grpc.WithTransportCredentials(transport))
		if err != nil {
			return true, errors.New("wisp_client_configuration_failed")
		}
		defer client.Close()
		return true, jumpserver.ProbeWisp(context.Background(), pb.NewServiceClient(client))
	case "healthcheck", "readycheck":
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
		path := "/healthy"
		if args[0] == "readycheck" {
			path = "/ready"
		}
		response, err := client.Get("http://" + net.JoinHostPort(host, port) + path)
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
