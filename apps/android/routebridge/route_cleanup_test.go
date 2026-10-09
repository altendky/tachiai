package routebridge

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestRouteCloseRetainsCheckedCleanupFailureForConcurrentCallers(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	route, err := newRouteWithCleanup(localUser, localPassword, localRealm, "owned-route.test",
		func(context.Context, string) (net.Conn, error) { return nil, errConnect }, func() error {
			calls.Add(1)
			close(started)
			<-release
			return errors.New("synthetic-private-cleanup-detail")
		})
	if err != nil {
		t.Fatal(err)
	}
	var callers sync.WaitGroup
	results := make(chan error, 8)
	for range 8 {
		callers.Add(1)
		go func() { defer callers.Done(); results <- route.Close() }()
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("Cleanup did not start")
	}
	select {
	case <-results:
		t.Fatal("Close reported completion before cleanup finished")
	default:
	}
	close(release)
	finished := make(chan struct{})
	go func() { callers.Wait(); close(finished) }()
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("Checked cleanup did not complete within fixture bound")
	}
	for range 8 {
		if err := <-results; err != errRouteCleanup || err.Error() != "Route cleanup unconfirmed" {
			t.Fatal("Cleanup failure was lost or private text escaped")
		}
	}
	if calls.Load() != 1 || route.Close() != errRouteCleanup {
		t.Fatal("Concurrent or repeated cleanup lost sticky failure")
	}
}
