package routebridge

import (
	"context"
	"sync/atomic"
)

// RoutePreparation makes cancellation available before a blocking native
// constructor starts. It contains no configuration, listener or native handle.
// Each backend gets one token; the playback owner may signal several tokens.
type RoutePreparation struct {
	ctx    context.Context
	cancel context.CancelFunc
	used   atomic.Bool
}

func NewRoutePreparation() *RoutePreparation {
	ctx, cancel := context.WithCancel(context.Background())
	return &RoutePreparation{ctx: ctx, cancel: cancel}
}

// Cancel only signals context cancellation. Native stop/join/destruction remains
// with the worker; callers must not make UI cancellation wait for teardown.
func (p *RoutePreparation) Cancel() {
	if p != nil && p.cancel != nil {
		p.cancel()
	}
}

// Native factories consume one token once. No configuration or errors from a
// native engine enter this shared boundary.
func (p *RoutePreparation) begin() (context.Context, error) {
	if p == nil || p.ctx == nil {
		return nil, errConfig
	}
	if p.ctx.Err() != nil {
		return nil, errClosed
	}
	if !p.used.CompareAndSwap(false, true) {
		return nil, errConfig
	}
	if p.ctx.Err() != nil {
		return nil, errClosed
	}
	return p.ctx, nil
}
