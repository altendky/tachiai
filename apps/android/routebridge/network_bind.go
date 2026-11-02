package routebridge

import (
	"context"
	"net"
	"sync"
	"sync/atomic"

	"golang.zx2c4.com/wireguard/conn"
)

// The delegate never escapes this wrapper. In particular only this lifecycle
// lock may close descriptors while a synchronous borrowed-fd callback runs.
type networkBind struct {
	inner         conn.Bind
	fds           func() ([]int, error)
	network       *boundNetwork
	lifecycle     sync.Mutex
	sends         sync.RWMutex
	ready         atomic.Bool
	generation    atomic.Uint64
	cleanupFailed atomic.Bool
}

func (b *networkBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	if b.cleanupFailed.Load() {
		return nil, 0, errRouteCleanup
	}
	if b.ready.Load() {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	b.sends.Lock()
	defer b.sends.Unlock()
	if !b.network.active(context.Background()) {
		return nil, 0, errClosed
	}
	fns, actual, err := b.inner.Open(port)
	rollback := func() ([]conn.ReceiveFunc, uint16, error) {
		if b.inner.Close() != nil {
			b.cleanupFailed.Store(true)
			b.network.unconfirmed()
			return nil, 0, errRouteCleanup
		}
		return nil, 0, errConnect
	}
	if err != nil {
		return rollback()
	}
	fds, err := b.fds()
	if err != nil || len(fds) < 1 || len(fds) > 2 || len(fns) != len(fds) {
		return rollback()
	}
	seen := make(map[int]bool)
	for _, fd := range fds {
		if seen[fd] || b.network.bind(context.Background(), fd) != nil {
			return rollback()
		}
		seen[fd] = true
	}
	if !b.network.active(context.Background()) {
		return rollback()
	}
	gen := b.generation.Add(1)
	result := make([]conn.ReceiveFunc, len(fns))
	for i, fn := range fns {
		result[i] = func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
			if !b.ready.Load() || b.generation.Load() != gen || !b.network.active(context.Background()) {
				return 0, net.ErrClosed
			}
			n, err := fn(packets, sizes, endpoints)
			if !b.ready.Load() || b.generation.Load() != gen || !b.network.active(context.Background()) {
				return 0, net.ErrClosed
			}
			return n, err
		}
	}
	b.ready.Store(true)
	return result, actual, nil
}
func (b *networkBind) Close() error {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.ready.Store(false)
	b.generation.Add(1)
	// Close sockets FIRST: blocked Send must be interrupted without needing its
	// send read-lock. Then join sends before another Open may publish new fds.
	if b.inner.Close() != nil {
		b.cleanupFailed.Store(true)
		b.network.unconfirmed()
	}
	b.sends.Lock()
	b.sends.Unlock()
	if b.cleanupFailed.Load() {
		return errRouteCleanup
	}
	return nil
}
func (b *networkBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	b.sends.RLock()
	defer b.sends.RUnlock()
	if !b.ready.Load() || !b.network.active(context.Background()) {
		return net.ErrClosed
	}
	return b.inner.Send(packets, endpoint)
}
func (b *networkBind) SetMark(mark uint32) error {
	// A generic SO_MARK write could erase the Android Network's socket mark.
	if mark != 0 {
		return errConfig
	}
	return nil
}
func (b *networkBind) BatchSize() int { return b.inner.BatchSize() }
func (b *networkBind) ParseEndpoint(raw string) (conn.Endpoint, error) {
	return b.inner.ParseEndpoint(raw)
}
