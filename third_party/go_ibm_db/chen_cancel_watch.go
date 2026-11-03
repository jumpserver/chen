package go_ibm_db

import (
	"context"
	"sync"
	"time"
)

// The caller joins this per-call watcher before diagnostics, handle cleanup or
// reuse. A late cancel can affect this call's cursor, never a subsequent call.
func watchNativeCancellation(ctx context.Context, cancel func()) func() {
	if ctx.Done() == nil {
		return func() {}
	}
	done, stopped := make(chan struct{}), make(chan struct{})
	var mu sync.Mutex
	active := true
	go func() {
		defer close(stopped)
		select {
		case <-done:
			return
		case <-ctx.Done():
		}
		// Context cancellation can win the tiny gap before the C function enters.
		// SQLCancel then has no effect under ODBC3. Retry while this same call owns
		// the handle; stop() joins before diagnostics, cleanup or any next call.
		for {
			mu.Lock()
			if !active {
				mu.Unlock()
				return
			}
			cancel()
			mu.Unlock()
			timer := time.NewTimer(10 * time.Millisecond)
			select {
			case <-done:
				timer.Stop()
				return
			case <-timer.C:
			}
		}
	}()

	var once sync.Once
	return func() { once.Do(func() { mu.Lock(); active = false; mu.Unlock(); close(done); <-stopped }) }
}
