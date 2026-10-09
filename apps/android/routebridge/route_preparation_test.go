package routebridge

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestRoutePreparationPreCancelAndInvalidToken(t *testing.T) {
	p := NewRoutePreparation()
	p.Cancel()
	p.Cancel()
	if _, err := p.begin(); err != errClosed {
		t.Fatal("cancelled preparation was accepted")
	}
	for _, invalid := range []*RoutePreparation{nil, {}} {
		invalid.Cancel()
		if _, err := invalid.begin(); err != errConfig {
			t.Fatal("invalid preparation was accepted")
		}
	}
}

func TestRoutePreparationConcurrentConstructorsConsumeOnlyOnce(t *testing.T) {
	p := NewRoutePreparation()
	defer p.Cancel()
	var accepted atomic.Int32
	var workers sync.WaitGroup
	start := make(chan struct{})
	for range 32 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			if _, err := p.begin(); err == nil {
				accepted.Add(1)
			}
		}()
	}
	close(start)
	workers.Wait()
	if accepted.Load() != 1 {
		t.Fatal("a token was consumed by multiple constructors")
	}
}

func TestRoutePreparationCancelDoesNotJoinCleanupAndKeepsTokensIndependent(t *testing.T) {
	p, other := NewRoutePreparation(), NewRoutePreparation()
	defer other.Cancel()
	ctx, err := p.begin()
	if err != nil {
		t.Fatal(err)
	}
	otherContext, err := other.begin()
	if err != nil {
		t.Fatal(err)
	}
	cleanup := make(chan struct{})
	done := make(chan struct{})
	go func() { <-ctx.Done(); <-cleanup; close(done) }()
	defer func() { close(cleanup); <-done }()
	cancelled := make(chan struct{})
	go func() { p.Cancel(); close(cancelled) }()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("Cancel waited for worker cleanup")
	}
	if ctx.Err() == nil || otherContext.Err() != nil {
		t.Fatal("cancellation did not stay with its own preparation")
	}
	select {
	case <-done:
		t.Fatal("cleanup completed before the worker was released")
	default:
	}
}
