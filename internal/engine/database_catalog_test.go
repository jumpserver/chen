package engine

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/jumpserver/chen/internal/jumpserver"
)

type catalogDeadlineRecorder struct {
	net.Conn
	readAt, writeAt time.Time
}

func (c *catalogDeadlineRecorder) Read(p []byte) (int, error)  { return len(p), nil }
func (c *catalogDeadlineRecorder) Write(p []byte) (int, error) { return len(p), nil }
func (c *catalogDeadlineRecorder) SetReadDeadline(t time.Time) error {
	c.readAt = t
	return nil
}
func (c *catalogDeadlineRecorder) SetWriteDeadline(t time.Time) error {
	c.writeAt = t
	return nil
}

func TestCatalogSocketDeadlines(t *testing.T) {
	raw := &catalogDeadlineRecorder{}
	dialer := catalogSocketDialer{
		dial:         func(context.Context, string, string) (net.Conn, error) { return raw, nil },
		writeTimeout: 10 * time.Second,
	}
	conn, err := dialer.DialContext(context.Background(), "tcp", "test")
	if err != nil {
		t.Fatal(err)
	}
	p := []byte{1}
	before := time.Now()
	if _, err = conn.Read(p); err != nil || raw.readAt.Before(before.Add(120*time.Minute)) {
		t.Fatalf("read deadline %v, error %v", raw.readAt, err)
	}
	if _, err = conn.Write(p); err != nil || raw.writeAt.After(time.Now().Add(10*time.Second)) || raw.writeAt.Before(before.Add(10*time.Second)) {
		t.Fatalf("write deadline %v, error %v", raw.writeAt, err)
	}
	cancelAt := time.Now()
	if err = conn.SetDeadline(cancelAt); err != nil {
		t.Fatal(err)
	}
	_, _ = conn.Read(p)
	_, _ = conn.Write(p)
	if !raw.readAt.Equal(cancelAt) || !raw.writeAt.Equal(cancelAt) {
		t.Fatal("read or write renewed past a driver cancellation deadline")
	}
	if err = conn.SetDeadline(time.Time{}); err != nil {
		t.Fatal(err)
	}
	before = time.Now()
	_, _ = conn.Read(p)
	if raw.readAt.Before(before.Add(120 * time.Minute)) {
		t.Fatal("next read retained an earlier deadline")
	}
}

func TestCatalogHTTPReadOnReusedConnection(t *testing.T) {
	raw := &catalogDeadlineRecorder{}
	c := &catalogSocket{Conn: raw, read: socketDeadline{timeout: 30 * time.Second}, http: true}
	p := []byte{1}
	if _, err := c.Read(p); err != nil {
		t.Fatal(err)
	}
	beforeRequest := time.Now()
	if _, err := c.Write(p); err != nil {
		t.Fatal(err)
	}
	if raw.readAt.Before(beforeRequest.Add(30*time.Second)) || raw.readAt.After(time.Now().Add(30*time.Second)) {
		t.Fatalf("request did not renew the HTTP socket's 30s read deadline, got %v", raw.readAt)
	}
}

func TestCatalogHandshakeCancellation(t *testing.T) {
	for _, dialect := range []string{"mysql", "oceanbase-oracle", "dameng", "oracle", "sqlserver"} {
		t.Run(dialect, func(t *testing.T) {
			listener, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			cfg := jumpserver.ConnectionConfig{Dialect: dialect, Host: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port, User: "chen", Database: "test"}
			db, err := openCatalogDatabase(cfg, 1)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			result := make(chan error, 1)
			go func() {
				conn, err := db.Conn(ctx)
				if conn != nil {
					_ = conn.Close()
				}
				result <- err
			}()
			_ = listener.SetDeadline(time.Now().Add(3 * time.Second))
			peer, err := listener.Accept()
			if err != nil {
				t.Fatal(err)
			}
			defer peer.Close()
			cancel()
			_ = peer.SetReadDeadline(time.Now().Add(3 * time.Second))
			// Consume handshake bytes until the client closes its half-open connection.
			_, err = io.Copy(io.Discard, peer)
			var networkErr net.Error
			if errors.As(err, &networkErr) && networkErr.Timeout() {
				t.Fatal("cancelling login left the transport open")
			}
			select {
			case err := <-result:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("cancelled login returned %v", err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("login did not return after cancellation")
			}
		})
	}
}
