package routebridge

import (
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

// A private fd-owning test Bind: real loopback UDP, no unsafe inspection of the
// pinned delegate, default resolver, provider endpoint or Android Network claim.
type ownedUDPBind struct {
	mu         sync.Mutex
	v4, v6     *net.UDPConn
	done       chan struct{}
	blocked    bool
	sent       chan struct{}
	sentOnce   sync.Once
	closeFail  bool
	sends      atomic.Int32
	beforeSend func() error
}

func (b *ownedUDPBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.v4 != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	v4, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	actual := v4.LocalAddr().(*net.UDPAddr).Port
	v6, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.ParseIP("::1"), Port: actual})
	if err != nil {
		v4.Close()
		return nil, 0, err
	}
	b.v4 = v4
	b.v6 = v6
	b.done = make(chan struct{})
	receive := func(socket *net.UDPConn) conn.ReceiveFunc {
		return func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
			n, source, err := socket.ReadFromUDPAddrPort(packets[0])
			if err != nil {
				return 0, err
			}
			sizes[0] = n
			endpoints[0] = &conn.StdNetEndpoint{AddrPort: source}
			return 1, nil
		}
	}
	return []conn.ReceiveFunc{receive(v4), receive(v6)}, uint16(actual), nil
}
func (b *ownedUDPBind) fds() ([]int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.v4 == nil || b.v6 == nil {
		return nil, errClosed
	}
	var fds []int
	for _, socket := range []*net.UDPConn{b.v4, b.v6} {
		raw, err := socket.SyscallConn()
		if err != nil {
			return nil, err
		}
		if err := raw.Control(func(fd uintptr) { fds = append(fds, int(fd)) }); err != nil {
			return nil, err
		}
	}
	return fds, nil
}
func (b *ownedUDPBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.v4 != nil {
		b.v4.Close()
		b.v6.Close()
		b.v4 = nil
		b.v6 = nil
		close(b.done)
	}
	if b.closeFail {
		return errors.New("owned-private-close")
	}
	return nil
}
func (b *ownedUDPBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	b.mu.Lock()
	socket := b.v4
	if endpoint.DstIP().Is6() {
		socket = b.v6
	}
	done := b.done
	b.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	b.sends.Add(1)
	if b.beforeSend != nil {
		if err := b.beforeSend(); err != nil {
			return err
		}
	}
	if b.blocked {
		b.sentOnce.Do(func() { close(b.sent) })
		<-done
		return net.ErrClosed
	}
	for _, packet := range packets {
		if _, err := socket.WriteToUDPAddrPort(packet, endpoint.(*conn.StdNetEndpoint).AddrPort); err != nil {
			return err
		}
	}
	return nil
}
func (b *ownedUDPBind) ParseEndpoint(raw string) (conn.Endpoint, error) {
	address, err := netip.ParseAddrPort(raw)
	if err != nil {
		return nil, err
	}
	return &conn.StdNetEndpoint{AddrPort: address}, nil
}
func (b *ownedUDPBind) BatchSize() int       { return 1 }
func (b *ownedUDPBind) SetMark(uint32) error { return nil }
func boundUDPFixture(t *testing.T, inner *ownedUDPBind, binding *fixtureNetworkBinding) *networkBind {
	t.Helper()
	network, err := newBoundNetwork(binding)
	if err != nil {
		t.Fatal("Owned Network capability unavailable")
	}
	bind := &networkBind{inner: inner, network: network, fds: inner.fds}
	t.Cleanup(func() { bind.Close(); network.stop() })
	return bind
}
func TestNetworkBindPublishesOnlyAfterBothFamiliesAndRebindsEveryOpen(t *testing.T) {
	binding := networkFixture()
	inner := &ownedUDPBind{}
	bind := boundUDPFixture(t, inner, binding)
	if err := bind.Send([][]byte{{1}}, &conn.StdNetEndpoint{}); !errors.Is(err, net.ErrClosed) {
		t.Fatal("Unopened Bind sent data")
	}
	fns, _, err := bind.Open(0)
	if err != nil {
		t.Fatal("Owned dual-stack Bind did not open")
	}
	if len(fns) != 2 || binding.binds.Load() != 2 {
		t.Fatal("Receive funcs exposed before both fd callbacks")
	}
	if bind.SetMark(1) != errConfig || bind.SetMark(0) != nil {
		t.Fatal("Generic mark could override captured Network")
	}
	if bind.Close() != nil {
		t.Fatal("Owned Bind close failed")
	}
	if _, _, err := bind.Open(0); err != nil || binding.binds.Load() != 4 {
		t.Fatal("Reopen omitted Network binding")
	}
	packets := [][]byte{make([]byte, 32)}
	if _, err := fns[0](packets, make([]int, 1), make([]conn.Endpoint, 1)); !errors.Is(err, net.ErrClosed) {
		t.Fatal("Old receive function survived Close/reopen")
	}
	binding.Cancel()
	if err := bind.Send([][]byte{{1}}, &conn.StdNetEndpoint{}); !errors.Is(err, net.ErrClosed) || inner.sends.Load() != 0 {
		t.Fatal("Retired Network still sent")
	}
}
func TestNetworkBindFailureNeverPublishesPartialFamilies(t *testing.T) {
	for _, mode := range []string{"second-bind", "missing-fd", "retired"} {
		t.Run(mode, func(t *testing.T) {
			binding := networkFixture()
			inner := &ownedUDPBind{}
			bind := boundUDPFixture(t, inner, binding)
			binding.bind = func(int32) error {
				if mode == "retired" {
					binding.Cancel()
				}
				if binding.binds.Load() == 2 {
					return errConnect
				}
				return nil
			}
			if mode == "missing-fd" {
				bind.fds = func() ([]int, error) { fds, err := inner.fds(); return fds[:1], err }
			}
			fns, port, err := bind.Open(0)
			if err == nil || len(fns) != 0 || port != 0 {
				t.Fatal("Partial or retired fd set was exposed")
			}
			inner.mu.Lock()
			closed := inner.v4 == nil && inner.v6 == nil
			inner.mu.Unlock()
			if !closed {
				t.Fatal("Partial fd set was not closed")
			}
			if bind.ready.Load() || inner.sends.Load() != 0 {
				t.Fatal("Failed binding admitted a send")
			}
		})
	}
}
func TestNetworkBindCloseKeepsBorrowedFDAliveAndRejectsLateBindSuccess(t *testing.T) {
	binding := networkFixture()
	started := make(chan struct{})
	release := make(chan struct{})
	inner := &ownedUDPBind{}
	bind := boundUDPFixture(t, inner, binding)
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	binding.bind = func(int32) error {
		if binding.binds.Load() == 1 {
			close(started)
			<-release
		}
		return nil
	}
	opened := make(chan error, 1)
	go func() { _, _, err := bind.Open(0); opened <- err }()
	awaitNetwork(t, started)
	inner.mu.Lock()
	endpoint, _ := inner.ParseEndpoint(inner.v4.LocalAddr().String())
	inner.mu.Unlock()
	sent := make(chan error, 1)
	go func() { sent <- bind.Send([][]byte{{1}}, endpoint) }()
	if inner.sends.Load() != 0 {
		t.Fatal("Send reached native socket before binding completed")
	}
	closed := make(chan error, 1)
	go func() { closed <- bind.Close() }()
	binding.Cancel()
	inner.mu.Lock()
	alive := inner.v4 != nil && inner.v6 != nil
	inner.mu.Unlock()
	if !alive {
		t.Fatal("Close invalidated fd while callback was active")
	}
	select {
	case <-closed:
		t.Fatal("Close escaped synchronous borrowed-fd callback")
	default:
	}
	close(release)
	if awaitNetwork(t, opened) == nil {
		t.Fatal("Late bound result reopened retired Network")
	}
	if awaitNetwork(t, closed) != nil {
		t.Fatal("Owned close did not settle")
	}
	if !errors.Is(awaitNetwork(t, sent), net.ErrClosed) || inner.sends.Load() != 0 {
		t.Fatal("Cancelled binding exposed a send")
	}
}
func TestNetworkBindCloseInterruptsBlockedSendBeforeJoiningSendLock(t *testing.T) {
	binding := networkFixture()
	inner := &ownedUDPBind{blocked: true, sent: make(chan struct{})}
	bind := boundUDPFixture(t, inner, binding)
	t.Cleanup(func() { inner.Close() })
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal("Owned Bind did not open")
	}
	ep, _ := inner.ParseEndpoint("127.0.0.1:9")
	sent := make(chan error, 1)
	go func() { sent <- bind.Send([][]byte{{1}}, ep) }()
	awaitNetwork(t, inner.sent)
	closed := make(chan error, 1)
	go func() { closed <- bind.Close() }()
	if awaitNetwork(t, closed) != nil || awaitNetwork(t, sent) == nil {
		t.Fatal("Close did not interrupt and join blocked Send")
	}
}
func TestNetworkBindCleanupFailureIsStickyAndPreventsReopen(t *testing.T) {
	binding := networkFixture()
	inner := &ownedUDPBind{closeFail: true}
	bind := boundUDPFixture(t, inner, binding)
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal("Owned Bind did not open")
	}
	if bind.Close() != errRouteCleanup {
		t.Fatal("Bind close failure was hidden")
	}
	inner.closeFail = false
	if bind.Close() != errRouteCleanup {
		t.Fatal("Later successful Close upgraded failure")
	}
	if _, _, err := bind.Open(0); err != errRouteCleanup {
		t.Fatal("Uncertain Bind was reused")
	}
}

// Keep these assertions close to the fixture: it satisfies the same public Bind
// shape as the Android delegate, but proves no Android Network acceptance.
var _ conn.Bind = (*ownedUDPBind)(nil)
var _ conn.Bind = (*networkBind)(nil)
var _ NetworkBinding = (*fixtureNetworkBinding)(nil)
