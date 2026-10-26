package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/jumpserver/chen/internal/engine"
	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/standalone"
	pb "github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
)

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
func main() {
	if err := run(); err != nil {
		slog.Error(err.Error())
		os.Exit(1)
	}
}

type options struct {
	listen, wisp, state, origins, ca, serverName, backends, webDir string
	single, plaintext, insecureBackends                            bool
	timeout                                                        time.Duration
	rows, bytes                                                    int
}

func parseOptions(args []string) (options, error) {
	o := options{}
	if len(args) > 0 && (args[0] == "single" || args[0] == "standalone") {
		o.single = true
		args = args[1:]
	}
	flags := flag.NewFlagSet("chen-go [single]", flag.ContinueOnError)
	flags.BoolVar(&o.single, "single", o.single, "Run independently using a local backend file; no Core or Wisp")
	flags.StringVar(&o.listen, "listen", env("CHEN_HTTP_ADDR", "127.0.0.1:8083"), "HTTP host:port (single mode defaults to loopback)")
	flags.StringVar(&o.wisp, "wisp-address", env("CHEN_WISP_ADDRESS", "127.0.0.1:9090"), "Wisp gRPC host:port (managed mode only)")
	flags.StringVar(&o.state, "state-dir", env("CHEN_STATE_DIR", "data/go"), "Persistent private journal and audit directory")
	flags.StringVar(&o.origins, "allowed-origins", env("CHEN_ALLOWED_ORIGINS", ""), "Additional exact trusted origins, comma separated")
	flags.StringVar(&o.ca, "wisp-ca", env("CHEN_WISP_CA", ""), "CA file for TLS to Wisp")
	flags.StringVar(&o.serverName, "wisp-server-name", env("CHEN_WISP_SERVER_NAME", ""), "Wisp TLS certificate name")
	flags.BoolVar(&o.plaintext, "wisp-insecure", false, "Explicitly allow plaintext gRPC to Wisp on a private network")
	flags.StringVar(&o.backends, "backends", "", "Single mode database YAML file (default standalone_backends.yml)")
	flags.StringVar(&o.backends, "b", "", "Alias for --backends")
	flags.BoolVar(&o.insecureBackends, "allow-insecure-backend-file", false, "Allow group/other permissions on the backend file")
	flags.StringVar(&o.webDir, "web-dir", "", "Single mode Luna static output directory (pnpm generate:chen)")
	flags.DurationVar(&o.timeout, "query-timeout", time.Minute, "Maximum queued plus running time per execution")
	flags.IntVar(&o.rows, "max-rows", 10000, "Maximum retained rows per result set")
	flags.IntVar(&o.bytes, "max-result-bytes", 16<<20, "Maximum retained row data bytes per session")
	if err := flags.Parse(args); err != nil {
		return o, err
	}
	if flags.NArg() != 0 {
		return o, errors.New("unexpected command argument")
	}
	if o.timeout <= 0 || o.rows <= 0 || o.bytes <= 0 {
		return o, errors.New("invalid resource limits")
	}
	if !o.single && (o.backends != "" || o.insecureBackends || o.webDir != "") {
		return o, errors.New("backends and web-dir require single mode")
	}
	if o.single && o.backends == "" {
		o.backends = "standalone_backends.yml"
	}
	return o, nil
}

// Only the selected mode initializes its provider. Single mode never constructs
// a Wisp client or starts the managed audit/control background processes.
func configuredProvider(o options) (jumpserver.Provider, func(), error) {
	if o.single {
		config, err := standalone.Load(o.backends, standalone.LoadOptions{AllowInsecurePermissions: o.insecureBackends})
		if err != nil {
			return nil, nil, err
		}
		p, err := standalone.NewProvider(config, filepath.Join(o.state, "audit"))
		return p, func() {}, err
	}
	transport, err := wispCredentials(o.wisp, o.ca, o.serverName, o.plaintext)
	if err != nil {
		return nil, nil, err
	}
	client, err := grpc.NewClient(o.wisp, grpc.WithTransportCredentials(transport))
	if err != nil {
		return nil, nil, errors.New("wisp_client_configuration_failed")
	}
	p, err := jumpserver.NewWispProvider(pb.NewServiceClient(client), jumpserver.Options{SpoolDir: filepath.Join(o.state, "outbox"), OnError: func(err error) { slog.Warn(err.Error()) }})
	if err != nil {
		_ = client.Close()
		return nil, nil, errors.New("audit_outbox_initialization_failed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); p.Run(ctx) }()
	return p, func() {
		cancel()
		select {
		case <-done:
		case <-time.After(12 * time.Second):
			slog.Warn("audit_shutdown_deadline_exceeded_pending_outbox_will_retry_on_restart")
		}
		_ = client.Close()
	}, nil
}

func run() error {
	if handled, err := runUtility(os.Args[1:], os.Stdout); handled {
		return err
	}
	o, err := parseOptions(os.Args[1:])
	if errors.Is(err, flag.ErrHelp) {
		return nil
	}
	if err != nil {
		return err
	}
	provider, stopProvider, err := configuredProvider(o)
	if err != nil {
		return err
	}
	defer stopProvider()
	trusted := []string{}
	for _, s := range strings.Split(o.origins, ",") {
		if s = strings.TrimSpace(s); s != "" {
			trusted = append(trusted, s)
		}
	}
	app := engine.New(provider, engine.Options{SingleMode: o.single, StateDir: o.state, AllowedOrigins: trusted, QueryTimeout: o.timeout, MaxRows: o.rows, MaxResultBytes: o.bytes})
	handler, closeWeb, err := webHandler(app.Handler(), o)
	if err != nil {
		app.Close()
		return err
	}
	defer closeWeb()
	server := &http.Server{Addr: o.listen, Handler: handler, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	failure := make(chan error, 1)
	go func() { failure <- server.ListenAndServe() }()
	stopCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	mode := "managed"
	if o.single {
		mode = "single"
	}
	slog.Info("chen_go_started", "listen", o.listen, "mode", mode, "transport", "http+sse")
	var serveErr error
	select {
	case <-stopCtx.Done():
	case serveErr = <-failure:
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
	closeCtx, closeCancel := context.WithTimeout(context.Background(), 15*time.Second)
	cleanupErr := app.CloseContext(closeCtx)
	closeCancel()
	if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
		return errors.New("http_listener_failed")
	}
	return sessionCleanupError(cleanupErr)
}

func sessionCleanupError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, jumpserver.ErrAuditDisk) {
		return errors.New("session_cleanup_audit_persistence_failed")
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return errors.New("session_cleanup_deadline_exceeded")
	}
	return errors.New("session_cleanup_failed")
}
func wispCredentials(address, caFile, name string, allowPlaintext bool) (credentials.TransportCredentials, error) {
	if caFile != "" {
		pem, err := os.ReadFile(caFile)
		if err != nil {
			return nil, errors.New("wisp_ca_unreadable")
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			return nil, errors.New("wisp_ca_invalid")
		}
		return credentials.NewTLS(&tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool, ServerName: name}), nil
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("wisp_address_must_be_host_port")
	}
	ip := net.ParseIP(host)
	if !allowPlaintext && host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		return nil, errors.New("remote_wisp_requires_tls_ca_or_explicit_wisp_insecure")
	}
	return insecure.NewCredentials(), nil
}
