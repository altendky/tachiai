package routebridge

import (
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
)

func echoDial(t *testing.T) dialFunc {
	t.Helper()
	origin, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("Fixture origin failed")
	}
	t.Cleanup(func() { origin.Close() })
	go func() {
		for {
			c, e := origin.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	return func(ctx context.Context, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "tcp4", origin.Addr().String())
	}
}

func authFor(user, password string) string {
	return "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+password)) + "\r\n"
}

func echoes(t *testing.T, r *Route, target, auth string) int {
	t.Helper()
	c, reader, status := connect(t, r, target, auth)
	defer c.Close()
	if status == 200 {
		io.WriteString(c, "ping")
		buffer := make([]byte, 4)
		if _, err := io.ReadFull(reader, buffer); err != nil || string(buffer) != "ping" {
			t.Fatal("Echo through lease failed")
		}
	}
	return status
}

func TestEngineLeasesKeepTheirOwnCredentialsAndHosts(t *testing.T) {
	var cleanups atomic.Int32
	engine := newEngine(echoDial(t), func() error { cleanups.Add(1); return nil })
	a, err := engine.Lease(localUser, localPassword, localRealm, "a.example.test")
	if err != nil {
		t.Fatal(err)
	}
	const otherUser, otherPassword = "synthetic_other_user", "synthetic_other_password"
	b, err := engine.Lease(otherUser, otherPassword, localRealm, "b.example.test")
	if err != nil {
		t.Fatal(err)
	}
	if a.Port() == b.Port() || engine.LeaseCount() != 2 {
		t.Fatal("Leases must own separate listeners")
	}
	if echoes(t, a, "a.example.test:443", localAuth()) != 200 || echoes(t, b, "b.example.test:443", authFor(otherUser, otherPassword)) != 200 {
		t.Fatal("Each lease reaches its own host")
	}
	if echoes(t, a, "b.example.test:443", localAuth()) != 403 || echoes(t, b, "a.example.test:443", authFor(otherUser, otherPassword)) != 403 {
		t.Fatal("A lease cannot reach another lease's host")
	}
	if echoes(t, b, "b.example.test:443", localAuth()) != 407 {
		t.Fatal("A lease accepts only its own credentials")
	}
	if a.Close() != nil || engine.LeaseCount() != 1 || cleanups.Load() != 0 {
		t.Fatal("Closing one lease must leave the transport up")
	}
	if echoes(t, b, "b.example.test:443", authFor(otherUser, otherPassword)) != 200 {
		t.Fatal("The remaining lease keeps working")
	}
	if engine.Close() != nil || cleanups.Load() != 1 || engine.LeaseCount() != 0 {
		t.Fatal("Engine close releases the remaining lease and the transport once")
	}
	if b.Close() != nil || engine.Close() != nil || cleanups.Load() != 1 {
		t.Fatal("Repeated closes are idempotent")
	}
	if _, err := engine.Lease(localUser, localPassword, localRealm, "a.example.test"); !errors.Is(err, errClosed) {
		t.Fatal("A closed engine refuses new leases")
	}
}

func TestEngineReportsUnconfirmedTransportCleanup(t *testing.T) {
	engine := newEngine(echoDial(t), func() error { return errors.New("synthetic cleanup failure") })
	if _, err := engine.Lease(localUser, localPassword, localRealm, "a.example.test"); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(engine.Close(), errRouteCleanup) || !errors.Is(engine.Close(), errRouteCleanup) {
		t.Fatal("Cleanup failure stays reported")
	}
}

func TestEngineRefusesInvalidLeaseWithoutTouchingTransport(t *testing.T) {
	var cleanups atomic.Int32
	engine := newEngine(echoDial(t), func() error { cleanups.Add(1); return nil })
	if _, err := engine.Lease("short", localPassword, localRealm, "a.example.test"); !errors.Is(err, errConfig) {
		t.Fatal("Invalid credentials are refused")
	}
	if engine.LeaseCount() != 0 || cleanups.Load() != 0 || engine.Close() != nil || cleanups.Load() != 1 {
		t.Fatal("A refused lease leaves the engine unchanged")
	}
}

func TestWireGuardEngineServesConcurrentLeasesThroughOneDevice(t *testing.T) {
	engine, err := NewWireGuardEngine(wireGuardFixture(t))
	if err != nil {
		t.Fatal("WireGuard engine creation failed")
	}
	defer engine.Close()
	const otherUser, otherPassword = "synthetic_other_user", "synthetic_other_password"
	a, err := engine.Lease(localUser, localPassword, localRealm, "example.test")
	if err != nil {
		t.Fatal(err)
	}
	b, err := engine.Lease(otherUser, otherPassword, localRealm, "example.test")
	if err != nil {
		t.Fatal(err)
	}
	// Both leases hold open connections through the same device at once.
	ca, ra, sa := connect(t, a, "example.test:443", localAuth())
	defer ca.Close()
	cb, rb, sb := connect(t, b, "example.test:443", authFor(otherUser, otherPassword))
	defer cb.Close()
	if sa != 200 || sb != 200 {
		t.Fatal("WireGuard CONNECT failed")
	}
	for _, pair := range []struct {
		c     net.Conn
		r     io.Reader
		bytes string
	}{{ca, ra, "first-lease"}, {cb, rb, "second-lease"}, {ca, ra, "first-again"}} {
		io.WriteString(pair.c, pair.bytes)
		body := make([]byte, len(pair.bytes))
		if _, err := io.ReadFull(pair.r, body); err != nil || string(body) != pair.bytes {
			t.Fatal("Tunnel bytes did not arrive")
		}
	}
	if a.Close() != nil {
		t.Fatal("Lease close failed")
	}
	io.WriteString(cb, "after-close")
	body := make([]byte, len("after-close"))
	if _, err := io.ReadFull(rb, body); err != nil || string(body) != "after-close" {
		t.Fatal("The remaining lease lost the device")
	}
	if engine.Close() != nil {
		t.Fatal("Engine close failed")
	}
}
