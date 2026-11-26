package routebridge

import (
	"net"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

func boundEngineFixture(t *testing.T, inner *ownedUDPBind) (string, *fixtureNetworkBinding, *Engine) {
	t.Helper()
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal("Owned endpoint unavailable")
	}
	t.Cleanup(func() { peer.Close() })
	client, server := syntheticKeys(t)
	config := fixtureConfig(client, server, peer.LocalAddr().String())
	binding := networkFixture()
	engine, err := newWireGuardBoundEngine(config, binding, func(n *boundNetwork) (conn.Bind, error) {
		return &networkBind{inner: inner, network: n, fds: inner.fds}, nil
	})
	if err != nil {
		t.Fatal("Owned bound engine did not start")
	}
	t.Cleanup(func() { engine.Close() })
	return config, binding, engine
}

func TestNetworkWireGuardBoundEngineLendsOnePeerUntilConfirmedClose(t *testing.T) {
	config, binding, engine := boundEngineFixture(t, &ownedUDPBind{})
	if binding.binds.Load() != 2 {
		t.Fatal("Bound engine did not bind its outer sockets to the supplied Network")
	}
	a, err := engine.Lease(localUser, localPassword, localRealm, "origin.owned.test")
	if err != nil {
		t.Fatal(err)
	}
	const otherUser, otherPassword = "synthetic_other_user", "synthetic_other_password"
	b, err := engine.Lease(otherUser, otherPassword, localRealm, "other.owned.test")
	if err != nil || a.Port() == b.Port() || engine.LeaseCount() != 2 {
		t.Fatal("Bound engine leases must own separate listeners")
	}
	called := false
	refuse := func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig }
	if _, err := newWireGuardBoundEngine(config, networkFixture(), refuse); err != errConnect || called {
		t.Fatal("A second bound engine for the peer reached native work")
	}
	if _, err := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", networkFixture(), refuse); err != errConnect || called {
		t.Fatal("A bound route for the peer reached native work beside its engine")
	}
	if a.Close() != nil || engine.LeaseCount() != 1 || !binding.IsActive() {
		t.Fatal("Closing one lease must leave the bound device up")
	}
	if engine.Close() != nil || binding.IsActive() || binding.unknown.Load() || engine.LeaseCount() != 0 {
		t.Fatal("Bound engine did not close its leases and Network scope")
	}
	if b.Close() != nil {
		t.Fatal("A lease closed by its engine must still close once")
	}
	if _, err := engine.Lease(localUser, localPassword, localRealm, "origin.owned.test"); err != errClosed {
		t.Fatal("A closed bound engine lent another lease")
	}
	if _, err := newWireGuardBoundEngine(config, networkFixture(), refuse); err != errConfig || !called {
		t.Fatal("Confirmed bound engine cleanup did not release its peer")
	}
}

func TestNetworkWireGuardBoundEngineUnconfirmedCloseQuarantinesItsPeer(t *testing.T) {
	inner := &ownedUDPBind{}
	config, binding, engine := boundEngineFixture(t, inner)
	inner.mu.Lock()
	inner.closeFail = true
	inner.mu.Unlock()
	if engine.Close() != errRouteCleanup || !binding.unknown.Load() {
		t.Fatal("Unconfirmed bound engine cleanup was accepted")
	}
	next := networkFixture()
	called := false
	_, err := newWireGuardBoundEngine(config, next, func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
	if err != errRouteCleanup || called || len(next.resolved()) != 0 || next.binds.Load() != 0 {
		t.Fatal("Quarantined peer reached new native or DNS work")
	}
}
