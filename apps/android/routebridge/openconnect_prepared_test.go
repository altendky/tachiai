//go:build openconnect_validation && cgo

package routebridge

import (
	"context"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func ocPreparedAPI(profile openConnectProfile, preparation *RoutePreparation) *OpenConnectPreparedResult {
	return NewOpenConnectPrepared(profile.endpoint, profile.bootstrap, profile.ca, profile.certificate, profile.key, localUser, localPassword, localRealm, "owned-route.test", preparation)
}

// Trigger expiry only after the native worker reaches the owned stall.
type ocTriggeredDeadline struct{ done chan struct{} }

func (*ocTriggeredDeadline) Deadline() (time.Time, bool) { return time.Time{}, false }
func (c *ocTriggeredDeadline) Done() <-chan struct{}     { return c.done }
func (c *ocTriggeredDeadline) Err() error {
	select {
	case <-c.done:
		return context.DeadlineExceeded
	default:
		return nil
	}
}
func (*ocTriggeredDeadline) Value(any) any { return nil }

func TestOpenConnectPreparedAPISuccessAndIsolation(t *testing.T) {
	before := ocFDInventory(t)
	gateways := []*ocGateway{newOCGateway(t, "packets", "first"), newOCGateway(t, "packets", "second")}
	results := make([]*OpenConnectPreparedResult, 2)
	preparations := []*RoutePreparation{NewRoutePreparation(), NewRoutePreparation()}
	var workers sync.WaitGroup
	for i, gateway := range gateways {
		workers.Add(1)
		go func(index int, profile openConnectProfile) {
			defer workers.Done()
			results[index] = ocPreparedAPI(profile, preparations[index])
		}(i, gateway.profile)
	}
	workers.Wait()
	for i, result := range results {
		if result.GetCode() != 0 || result.GetRoute() == nil || result.GetRoute().GetPort() <= 0 {
			t.Fatalf("Prepared route unavailable: fixed code %d", result.GetCode())
		}
		route := result.GetRoute()
		t.Cleanup(func() { route.Close() })
		if second := ocPreparedAPI(gateways[i].profile, preparations[i]); second.GetCode() != 1 || second.GetRoute() != nil {
			t.Fatal("Preparation token reused")
		}
		response, err := ocClient(t, route, gateways[i].roots, false).Get("https://owned-route.test/")
		if err != nil {
			t.Fatal("Prepared authenticated HTTPS route failed")
		}
		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil || string(body) != gateways[i].label {
			t.Fatal("Prepared API exchanged independent route identity")
		}
	}
	for i, result := range results {
		for n := 0; n < 8; n++ {
			workers.Add(1)
			go func(route *Route) {
				defer workers.Done()
				if route.Close() != nil {
					t.Error("Prepared native cleanup unconfirmed")
				}
			}(result.GetRoute())
		}
		workers.Wait()
		if preparations[i].ctx.Err() == nil {
			t.Error("Route close did not cancel preparation lifetime")
		}
		gateways[i].Close()
	}
	ocAssertNoRetainedFD(t, before)
}

func TestOpenConnectPreparedAPIRejectsBeforeNetwork(t *testing.T) {
	for _, mode := range []string{"username", "password", "realm", "allowlist", "profile-bound", "bootstrap-nul", "pre-cancel", "missing-preparation"} {
		t.Run(mode, func(t *testing.T) {
			gateway := newOCGateway(t, "packets", "unused")
			before := ocFDInventory(t)
			p := NewRoutePreparation()
			profile := gateway.profile
			username, password, realm, allowed := localUser, localPassword, localRealm, "owned-route.test"
			code := 1
			switch mode {
			case "username":
				username = "bad\r\nusername"
			case "password":
				password = "bad"
			case "realm":
				realm = "bad"
			case "allowlist":
				allowed = "*.owned-route.test"
			case "profile-bound":
				profile.key = strings.Repeat("x", headerLimit)
			case "bootstrap-nul":
				profile.bootstrap = "127.0.0.1\x00x"
			case "pre-cancel":
				p.Cancel()
				code = 4
			case "missing-preparation":
				p = nil
			}
			result := NewOpenConnectPrepared(profile.endpoint, profile.bootstrap, profile.ca, profile.certificate, profile.key, username, password, realm, allowed, p)
			if result.GetCode() != code || result.GetRoute() != nil || gateway.accepts.Load() != 0 {
				t.Fatalf("Invalid/pre-cancelled setup escaped fixed pre-network boundary: %d", result.GetCode())
			}
			ocAssertNoRetainedFD(t, before)
		})
	}
}

func TestOpenConnectPreparedAPICancellationAndTimeout(t *testing.T) {
	for _, mode := range []string{"stall-auth", "stall-tls", "deadline"} {
		t.Run(mode, func(t *testing.T) {
			before := ocFDInventory(t)
			gatewayMode := mode
			if mode == "deadline" {
				gatewayMode = "stall-auth"
			}
			gateway := newOCGateway(t, gatewayMode, "unused")
			p := NewRoutePreparation()
			defer p.Cancel()
			code := 4
			if mode == "deadline" {
				deadline := &ocTriggeredDeadline{done: make(chan struct{})}
				var expire sync.Once
				p.ctx, p.cancel = deadline, func() { expire.Do(func() { close(deadline.done) }) }
				code = 5
			}
			done := make(chan *OpenConnectPreparedResult, 1)
			go func() { done <- ocPreparedAPI(gateway.profile, p) }()
			select {
			case <-gateway.entered:
			case <-time.After(3 * time.Second):
				t.Fatal("Owned preparation did not enter controlled stall")
			}
			start := time.Now()
			p.Cancel()
			if time.Since(start) > 100*time.Millisecond {
				t.Error("Caller cancellation blocked on native cleanup")
			}
			select {
			case result := <-done:
				if result.GetCode() != code || result.GetRoute() != nil {
					t.Fatalf("Cancellation/deadline fixed code mismatch: %d", result.GetCode())
				}
			case <-time.After(6 * time.Second):
				t.Fatal("Preparation did not complete bounded cleanup")
			}
			gateway.Close()
			ocAssertNoRetainedFD(t, before)
		})
	}
}

func TestOpenConnectPreparedAPIOpaqueTLSFailure(t *testing.T) {
	before := ocFDInventory(t)
	gateway := newOCGateway(t, "packets", "unused")
	profile := gateway.profile
	profile.ca = ocIdentityNew(t, nil, 77, "", false).certPEM
	result := ocPreparedAPI(profile, NewRoutePreparation())
	// The existing native enum has no TLS-specific reason; do not invent code 2.
	if result.GetCode() != 3 || result.GetRoute() != nil {
		t.Fatal("Opaque native refusal escaped fixed connection result")
	}
	gateway.Close()
	ocAssertNoRetainedFD(t, before)
}

func TestOpenConnectPreparedAPIUnconfirmedCleanupGuard(t *testing.T) {
	if openConnectUnconfirmed.Load() {
		t.Fatal("Unexpected prior native cleanup failure")
	}
	openConnectUnconfirmed.Store(true)
	defer openConnectUnconfirmed.Store(false) // Test isolation only; production never resets it.
	gateway := newOCGateway(t, "packets", "unused")
	before := ocFDInventory(t)
	for n := 0; n < 3; n++ {
		result := ocPreparedAPI(gateway.profile, NewRoutePreparation())
		if result.GetCode() != 6 || result.GetRoute() != nil || gateway.accepts.Load() != 0 {
			t.Fatal("Unconfirmed native cleanup allowed another setup")
		}
	}
	ocAssertNoRetainedFD(t, before)
}

func TestOpenConnectPreparedAPICleanupErrorIsSticky(t *testing.T) {
	closed := make(chan struct{})
	close(closed)
	tunnel := &openConnectPacketTunnel{stop: make(chan struct{}), done: closed, cleanupError: errRouteCleanup}
	route, err := newRouteWithCleanup(localUser, localPassword, localRealm, "owned-route.test", func(context.Context, string) (net.Conn, error) { return nil, errConnect }, tunnel.Close)
	if err != nil {
		t.Fatal("Owned cleanup fixture listener")
	}
	var workers sync.WaitGroup
	for n := 0; n < 8; n++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if route.Close() != errRouteCleanup || openConnectFailureCode(route.Close()) != 6 {
				t.Error("Cleanup failure lost its sticky fixed result")
			}
		}()
	}
	workers.Wait()
}
