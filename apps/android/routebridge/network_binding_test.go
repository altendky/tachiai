package routebridge

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

type fixtureNetworkBinding struct {
	active    atomic.Bool
	unknown   atomic.Bool
	pending   atomic.Int32
	binds     atomic.Int32
	mu        sync.Mutex
	hosts     []string
	resolve   func(string, int64) (string, error)
	bind      func(int32) error
	cancelled chan struct{}
	once      sync.Once
}

func networkFixture() *fixtureNetworkBinding {
	b := &fixtureNetworkBinding{cancelled: make(chan struct{})}
	b.active.Store(true)
	return b
}
func (b *fixtureNetworkBinding) BindSocket(fd int32) error {
	b.pending.Add(1)
	defer b.pending.Add(-1)
	b.binds.Add(1)
	if b.bind != nil {
		return b.bind(fd)
	}
	return nil
}
func (b *fixtureNetworkBinding) Resolve(host string, wait int64) (string, error) {
	b.pending.Add(1)
	defer b.pending.Add(-1)
	b.mu.Lock()
	b.hosts = append(b.hosts, host)
	b.mu.Unlock()
	if b.resolve != nil {
		return b.resolve(host, wait)
	}
	return "127.0.0.1", nil
}
func (b *fixtureNetworkBinding) Cancel() {
	b.active.Store(false)
	b.once.Do(func() { close(b.cancelled) })
}
func (b *fixtureNetworkBinding) IsActive() bool { return b.active.Load() }
func (b *fixtureNetworkBinding) ReportCleanupUnconfirmed() {
	b.unknown.Store(true)
	b.Cancel()
}
func (b *fixtureNetworkBinding) IsCleanupConfirmed() bool {
	return b.pending.Load() == 0 && !b.unknown.Load()
}
func (b *fixtureNetworkBinding) resolved() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]string(nil), b.hosts...)
}
func awaitNetwork[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case value := <-ch:
		return value
	case <-time.After(3 * time.Second):
		t.Fatal("Owned network event did not finish")
		var zero T
		return zero
	}
}

func TestNetworkCallbackPanicPermanentlyRefusesAdmissionAndReportsCleanup(t *testing.T) {
	binding := networkFixture()
	n, err := newBoundNetwork(binding)
	if err != nil {
		t.Fatal("Owned scope did not start")
	}
	binding.bind = func(int32) error { panic("owned callback refusal") }
	if n.bind(context.Background(), 1) != errConnect || !binding.unknown.Load() {
		t.Fatal("Callback panic did not report native cleanup uncertainty")
	}
	// Even an incorrectly revived callback cannot upgrade the Go owner.
	binding.active.Store(true)
	binding.unknown.Store(false)
	if n.active(context.Background()) || n.confirmed() || n.failure(errConnect) != errRouteCleanup {
		t.Fatal("Caught callback panic later admitted or confirmed work")
	}
}

func TestNetworkNumericResolutionNormalizesAndRejectsEntireUnsafeAnswer(t *testing.T) {
	addresses, err := numericAddresses("2001:0DB8:0000:0000:0000:0000:0000:0001\n2001:db8::1\n::ffff:127.0.0.1\n127.0.0.1")
	if err != nil || len(addresses) != 2 || addresses[0] != netip.MustParseAddr("2001:db8::1") || addresses[1] != netip.MustParseAddr("127.0.0.1") {
		t.Fatal("Numeric platform forms did not normalize/deduplicate")
	}
	for _, raw := range []string{"", "127.0.0.1\nowned.test", "https://127.0.0.1", "127.0.0.1:443", "::", "224.0.0.1", "fe80::1%eth0", strings.Repeat("127.0.0.1\n", 33), strings.Repeat("1", 4097)} {
		if _, err := numericAddresses(raw); err != errConnect {
			t.Fatal("Unsafe or unbounded platform answer admitted")
		}
	}
}
func TestNetworkSystemBindsBeforeNumericConnectAndKeepsOldFactoryUnchanged(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("Owned TCP listener unavailable")
	}
	defer listener.Close()
	b := networkFixture()
	var connected atomic.Bool
	b.bind = func(fd int32) error {
		if _, err := unix.Getpeername(int(fd)); err != unix.ENOTCONN {
			t.Error("Socket already connected at bind callback")
		}
		connected.Store(true)
		return nil
	}
	accepted := make(chan bool, 1)
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		accepted <- connected.Load()
		io.Copy(c, c)
	}()
	r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-route.test", b)
	if err != nil {
		t.Fatal("Bound System creation failed")
	}
	defer r.Close()
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	c, err := r.dial(context.Background(), net.JoinHostPort("owned-route.test", port))
	if err != nil {
		t.Fatal("Bound System owned dial failed")
	}
	defer c.Close()
	if !awaitNetwork(t, accepted) || b.binds.Load() != 1 || strings.Join(b.resolved(), ",") != "owned-route.test" {
		t.Fatal("Network resolution/binding order failed")
	}
	io.WriteString(c, "owned-opaque")
	data := make([]byte, len("owned-opaque"))
	if _, err := io.ReadFull(c, data); err != nil || string(data) != "owned-opaque" {
		t.Fatal("Opaque System bytes changed")
	}
	if r.Close() != nil || b.IsActive() {
		t.Fatal("Bound System did not retire its capability")
	}
	if _, err := r.dial(context.Background(), net.JoinHostPort("owned-route.test", port)); err != errConnect {
		t.Fatal("Retired capability redialed")
	}
	old, err := NewSystem(localUser, localPassword, localRealm, "owned-route.test")
	if err != nil {
		t.Fatal("Original System constructor changed")
	}
	if old.Close() != nil {
		t.Fatal("Original System close changed")
	}
}
func TestNetworkRefusalNeverConnectsOrFallsBack(t *testing.T) {
	for _, mode := range []string{"resolve", "malformed", "bind", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			b := networkFixture()
			b.resolve = func(string, int64) (string, error) {
				switch mode {
				case "resolve":
					return "", errors.New("private-resolver-detail")
				case "malformed":
					return "127.0.0.1\nambient.test", nil
				case "cancel":
					b.Cancel()
				}
				return "127.0.0.1\n127.0.0.2", nil
			}
			b.bind = func(int32) error { return errors.New("private-binding-detail") }
			r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-route.test", b)
			if err != nil {
				t.Fatal("Owned refusal setup failed")
			}
			defer r.Close()
			if connection, err := r.dial(context.Background(), "owned-route.test:443"); connection != nil || err != errConnect || err.Error() != "Route connection failed" {
				t.Fatal("Refusal connected or disclosed callback details")
			}
			want := int32(0)
			if mode == "bind" {
				want = 1
			}
			if b.binds.Load() != want {
				t.Fatal("Binding error retried another address or unsafe answer partially dialed")
			}
		})
	}
}
func TestNetworkRouteAdmissionPrecedesResolution(t *testing.T) {
	b := networkFixture()
	r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-route.test", b)
	if err != nil {
		t.Fatal("Owned admission setup failed")
	}
	defer r.Close()
	for _, input := range []struct {
		target, auth string
		status       int
	}{{"owned-route.test:443", "", 407}, {"outside.test:443", localAuth(), 403}, {"owned-route.test:80", localAuth(), 403}} {
		c, _, status := connect(t, r, input.target, input.auth)
		c.Close()
		if status != input.status {
			t.Fatal("Existing route admission changed")
		}
	}
	if len(b.resolved()) != 0 || b.binds.Load() != 0 {
		t.Fatal("Refused destination performed bound lookup or bind")
	}
}
func TestNetworkCloseCancelsDNSBeforeJoinAndPreservesUnknownLookupCleanup(t *testing.T) {
	for _, unknown := range []bool{false, true} {
		t.Run(fmt.Sprint(unknown), func(t *testing.T) {
			b := networkFixture()
			started := make(chan struct{})
			b.unknown.Store(unknown)
			b.resolve = func(_ string, wait int64) (string, error) {
				if wait < 1 || wait > 15000 {
					t.Error("Resolution hint escaped bound")
				}
				close(started)
				<-b.cancelled
				return "127.0.0.1", nil
			}
			r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-route.test", b)
			if err != nil {
				t.Fatal("Owned cancellation setup failed")
			}
			c, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", r.Port()))
			if err != nil {
				t.Fatal("Owned local proxy unavailable")
			}
			defer c.Close()
			io.WriteString(c, "CONNECT owned-route.test:443 HTTP/1.1\r\nHost: owned-route.test:443\r\n"+localAuth()+"\r\n")
			awaitNetwork(t, started)
			closed := make(chan error, 1)
			go func() { closed <- r.Close() }()
			result := awaitNetwork(t, closed)
			if b.binds.Load() != 0 {
				t.Fatal("Late resolution result dialed after cancellation")
			}
			if unknown {
				if result != errRouteCleanup {
					t.Fatal("Unfinished native lookup reported confirmed")
				}
				b.unknown.Store(false)
				if r.Close() != errRouteCleanup {
					t.Fatal("Late cleanup upgraded the original unconfirmed result")
				}
			} else if result != nil {
				t.Fatal("Completed cancelled lookup remained falsely uncertain")
			}
		})
	}
}
func TestNetworkCloseDoesNotReleaseBorrowedTCPFDWhileBinding(t *testing.T) {
	b := networkFixture()
	started, release := make(chan int32, 1), make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	b.bind = func(fd int32) error { started <- fd; <-release; return nil }
	r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-route.test", b)
	if err != nil {
		t.Fatal("Owned binding setup failed")
	}
	defer r.Close()
	c, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", r.Port()))
	if err != nil {
		t.Fatal("Owned local proxy unavailable")
	}
	defer c.Close()
	io.WriteString(c, "CONNECT owned-route.test:443 HTTP/1.1\r\nHost: owned-route.test:443\r\n"+localAuth()+"\r\n")
	fd := awaitNetwork(t, started)
	closed := make(chan error, 1)
	go func() { closed <- r.Close() }()
	awaitNetwork(t, b.cancelled)
	if _, err := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_TYPE); err != nil {
		t.Fatal("Borrowed fd closed while callback remained active")
	}
	select {
	case <-closed:
		t.Fatal("Close reported completion before synchronous binding finished")
	default:
	}
	close(release)
	if awaitNetwork(t, closed) != nil {
		t.Fatal("Actual completed binding cleanup was not confirmed")
	}
}
func TestNetworkHttpAndSocksBindOnlyTheirOuterProxyAndPreserveBytes(t *testing.T) {
	t.Run("http", func(t *testing.T) {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal("Owned HTTP proxy unavailable")
		}
		defer listener.Close()
		seen := make(chan bool, 1)
		b := networkFixture()
		var bound atomic.Bool
		b.bind = func(int32) error { bound.Store(true); return nil }
		go func() {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			defer c.Close()
			request, err := http.ReadRequest(bufio.NewReader(c))
			if err != nil {
				return
			}
			seen <- bound.Load() && request.Host == "origin.invalid:443" && request.Header.Get("Proxy-Authorization") == "Basic b3duZWQ6c2VjcmV0"
			io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
			io.Copy(c, c)
		}()
		_, port, _ := net.SplitHostPort(listener.Addr().String())
		r, err := NewHttpProxyBound("http://owned:secret@proxy.owned.test:"+port, localUser, localPassword, localRealm, "origin.invalid", b)
		if err != nil {
			t.Fatal("Bound HTTP proxy creation failed")
		}
		defer r.Close()
		c, reader, status := connect(t, r, "origin.invalid:443", localAuth())
		defer c.Close()
		if status != 200 || !awaitNetwork(t, seen) {
			t.Fatal("Bound HTTP auth/CONNECT ordering failed")
		}
		io.WriteString(c, "owned-http")
		bytes := make([]byte, len("owned-http"))
		if _, err := io.ReadFull(reader, bytes); err != nil || string(bytes) != "owned-http" {
			t.Fatal("HTTP opaque bytes changed")
		}
		if strings.Join(b.resolved(), ",") != "proxy.owned.test" || b.binds.Load() != 1 {
			t.Fatal("HTTP proxy performed local destination DNS")
		}
	})
	t.Run("socks", func(t *testing.T) {
		f := newSocksFixture(t, socksFixtureOptions{auth: true, method: 2, prefix: "owned-prefix"})
		b := networkFixture()
		config := strings.Replace(f.config(true), "127.0.0.1", "proxy.owned.test", 1)
		r, err := NewSocks5Bound(config, localUser, localPassword, localRealm, "origin.invalid", b)
		if err != nil {
			t.Fatal("Bound SOCKS creation failed")
		}
		defer r.Close()
		c, reader, status := connect(t, r, "origin.invalid:443", localAuth())
		defer c.Close()
		if status != 200 || awaitNetwork(t, f.requests) != "origin.invalid:443" {
			t.Fatal("Bound SOCKS remote DNS changed")
		}
		bytes := make([]byte, len("owned-prefix"))
		if _, err := io.ReadFull(reader, bytes); err != nil || string(bytes) != "owned-prefix" {
			t.Fatal("SOCKS opaque prefix changed")
		}
		if strings.Join(b.resolved(), ",") != "proxy.owned.test" || b.binds.Load() != 1 {
			t.Fatal("SOCKS proxy performed local destination DNS")
		}
	})
}

func TestNetworkSystemAcceptsPlatformIPv6AndBindsBeforeConnect(t *testing.T) {
	listener, err := net.Listen("tcp6", "[::1]:0")
	if err != nil {
		t.Fatal("Owned IPv6 listener unavailable")
	}
	defer listener.Close()
	b := networkFixture()
	b.resolve = func(string, int64) (string, error) { return "0:0:0:0:0:0:0:1", nil }
	var bound atomic.Bool
	b.bind = func(fd int32) error {
		address, err := unix.Getsockname(int(fd))
		if err != nil {
			return errConnect
		}
		if _, ok := address.(*unix.SockaddrInet6); !ok {
			return errConnect
		}
		bound.Store(true)
		return nil
	}
	accepted := make(chan bool, 1)
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		accepted <- bound.Load()
		c.Close()
	}()
	r, err := NewSystemBound(localUser, localPassword, localRealm, "owned-ipv6.test", b)
	if err != nil {
		t.Fatal("Owned bound IPv6 route unavailable")
	}
	defer r.Close()
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	connection, err := r.dial(context.Background(), net.JoinHostPort("owned-ipv6.test", port))
	if err != nil {
		t.Fatal("Platform IPv6 result could not dial")
	}
	connection.Close()
	if !awaitNetwork(t, accepted) || b.binds.Load() != 1 {
		t.Fatal("IPv6 connect preceded binding")
	}
}

func TestNetworkInvalidConstructorsRetireCapabilityWithoutExternalWork(t *testing.T) {
	constructors := []func(NetworkBinding) (*Route, error){
		func(b NetworkBinding) (*Route, error) {
			return NewSystemBound("short", localPassword, localRealm, "owned.test", b)
		},
		func(b NetworkBinding) (*Route, error) {
			return NewHttpProxyBound("https://proxy.owned.test:443", localUser, localPassword, localRealm, "owned.test", b)
		},
		func(b NetworkBinding) (*Route, error) {
			return NewSocks5Bound("invalid", localUser, localPassword, localRealm, "owned.test", b)
		},
		func(b NetworkBinding) (*Route, error) {
			return NewWireGuardBound("invalid", localUser, localPassword, localRealm, "owned.test", b)
		},
	}
	for _, construct := range constructors {
		b := networkFixture()
		route, err := construct(b)
		if route != nil || err != errConfig || b.IsActive() || len(b.resolved()) != 0 || b.binds.Load() != 0 {
			t.Fatal("Invalid constructor performed external work or kept admission")
		}
	}
}
