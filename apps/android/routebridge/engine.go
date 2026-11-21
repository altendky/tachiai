package routebridge

import "sync"

// Engine owns one transport and lends it to several loopback leases. Each
// lease is a Route with its own port, credentials, realm, allowed hosts and
// connection cap; closing a lease leaves the transport and other leases up.
type Engine struct {
	dial     dialFunc
	cleanup  func() error
	mu       sync.Mutex
	leases   map[*Route]struct{}
	closed   bool
	once     sync.Once
	closeErr error
}

func newEngine(dial dialFunc, cleanup func() error) *Engine {
	return &Engine{dial: dial, cleanup: cleanup, leases: make(map[*Route]struct{})}
}

// Lease opens one more loopback listener over the engine's transport.
func (e *Engine) Lease(username, password, realm, allowedHosts string) (*Route, error) {
	e.mu.Lock()
	closed := e.closed
	e.mu.Unlock()
	if closed {
		return nil, errClosed
	}
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, e.dial, nil)
	if err != nil {
		return nil, err
	}
	route.release = func() {
		e.mu.Lock()
		delete(e.leases, route)
		e.mu.Unlock()
	}
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		route.Close()
		return nil, errClosed
	}
	e.leases[route] = struct{}{}
	e.mu.Unlock()
	return route, nil
}

// LeaseCount reports leases that are open and not yet closing.
func (e *Engine) LeaseCount() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.leases)
}

// Close refuses new leases, closes the remaining ones, then releases the
// transport. Any unconfirmed step reports errRouteCleanup.
func (e *Engine) Close() error {
	e.once.Do(func() {
		e.mu.Lock()
		e.closed = true
		remaining := make([]*Route, 0, len(e.leases))
		for route := range e.leases {
			remaining = append(remaining, route)
		}
		e.mu.Unlock()
		for _, route := range remaining {
			if route.Close() != nil {
				e.closeErr = errRouteCleanup
			}
		}
		if e.cleanup != nil && e.cleanup() != nil {
			e.closeErr = errRouteCleanup
		}
	})
	return e.closeErr
}
