package routebridge

import (
	"encoding/hex"
	"io"
	"net"
	"net/netip"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type heldCloseWireGuardBind struct {
	conn.Bind
	hold    atomic.Bool
	entered chan struct{}
	release chan struct{}
	once    sync.Once
	refuse  bool
}

func (b *heldCloseWireGuardBind) Close() error {
	if !b.hold.Load() {
		return b.Bind.Close()
	}
	b.once.Do(func() { close(b.entered) })
	<-b.release
	if err := b.Bind.Close(); err != nil {
		return err
	}
	if b.refuse {
		return errRouteCleanup
	}
	return nil
}

func TestNetworkActualWireGuardTunnelKeepsInnerDNSAndBindsBeforeOuterHandshake(t *testing.T) {
	client, server := syntheticKeys(t)
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.2.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal("Owned peer netstack unavailable")
	}
	engine := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer engine.Close()
	ipc := "private_key=" + hex.EncodeToString(server.Bytes()) + "\nlisten_port=0\npublic_key=" + hex.EncodeToString(client.PublicKey().Bytes()) + "\nallowed_ip=10.2.0.2/32\n"
	if engine.IpcSet(ipc) != nil || engine.Up() != nil {
		t.Fatal("Owned peer did not start")
	}
	state, err := engine.IpcGet()
	if err != nil {
		t.Fatal("Owned peer port unavailable")
	}
	port := ""
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		t.Fatal("Owned peer did not bind")
	}
	udp, err := network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.2.0.1"), Port: 53})
	if err != nil {
		t.Fatal("Owned inner DNS unavailable")
	}
	defer udp.Close()
	dnsDone := make(chan struct{})
	go func() {
		defer close(dnsDone)
		data := make([]byte, 2048)
		for {
			n, source, err := udp.ReadFrom(data)
			if err != nil {
				return
			}
			var parser dnsmessage.Parser
			header, err := parser.Start(data[:n])
			if err != nil {
				continue
			}
			question, err := parser.Question()
			if err != nil {
				continue
			}
			builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: header.ID, Response: true, RecursionAvailable: true})
			builder.StartQuestions()
			builder.Question(question)
			builder.StartAnswers()
			if question.Type == dnsmessage.TypeA {
				builder.AResource(dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 30}, dnsmessage.AResource{A: [4]byte{10, 2, 0, 1}})
			}
			response, err := builder.Finish()
			if err == nil {
				udp.WriteTo(response, source)
			}
		}
	}()
	defer func() { udp.Close(); <-dnsDone }()
	tcp, err := network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.2.0.1"), Port: 443})
	if err != nil {
		t.Fatal("Owned inner TCP unavailable")
	}
	defer tcp.Close()
	originDone := make(chan struct{})
	originConnection := make(chan net.Conn, 1)
	go func() {
		defer close(originDone)
		c, err := tcp.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		originConnection <- c
		io.Copy(c, c)
	}()
	defer func() {
		tcp.Close()
		select {
		case c := <-originConnection:
			c.Close()
		default:
		}
		awaitNetwork(t, originDone)
	}()
	binding := networkFixture()
	inner := &ownedUDPBind{}
	inner.beforeSend = func() error {
		if binding.binds.Load() != 2 {
			return errConnect
		}
		return nil
	}
	r, err := newWireGuardBound(fixtureConfig(client, server, "outer.owned.test:"+port), localUser, localPassword, localRealm, "origin.owned.test", binding,
		func(n *boundNetwork) (conn.Bind, error) {
			return &networkBind{inner: inner, network: n, fds: inner.fds}, nil
		})
	if err != nil {
		t.Fatal("Owned bound WireGuard did not start")
	}
	defer r.Close()
	c, reader, status := connect(t, r, "origin.owned.test:443", localAuth())
	defer c.Close()
	if status != 200 || binding.binds.Load() != 2 || strings.Join(binding.resolved(), ",") != "outer.owned.test" {
		t.Fatal("Outer binding or inner-only DNS boundary failed")
	}
	io.WriteString(c, "owned-bound-tunnel")
	data := make([]byte, len("owned-bound-tunnel"))
	if _, err := io.ReadFull(reader, data); err != nil || string(data) != "owned-bound-tunnel" {
		t.Fatal("Actual bound tunnel did not carry opaque bytes")
	}
	if inner.sends.Load() == 0 {
		t.Fatal("Owned test did not exercise outer Send")
	}
	if r.Close() != nil || binding.IsActive() {
		t.Fatal("Owned engine and binding did not close")
	}
}
func TestNetworkHostWireGuardRefusesUnavailablePinnedFDAccessBeforeWork(t *testing.T) {
	if runtime.GOOS == "android" {
		t.Skip("Android has the pinned fd-access implementation")
	}
	client, server := syntheticKeys(t)
	binding := networkFixture()
	r, err := NewWireGuardBound(fixtureConfig(client, server, "outer.owned.test:51820"), localUser, localPassword, localRealm, "origin.owned.test", binding)
	if r != nil || err != errConfig || len(binding.resolved()) != 0 || binding.binds.Load() != 0 || binding.IsActive() {
		t.Fatal("Host fd gap silently fell back or allocated work")
	}
}

func TestNetworkWireGuardFailedRollbackRetainsActualOwnerAndQuarantinesOnlyItsPeer(t *testing.T) {
	client, server := syntheticKeys(t)
	config := fixtureConfig(client, server, "outer.owned.test:51820")
	parsed, err := parseWireGuard(config)
	if err != nil {
		t.Fatal("Owned config invalid")
	}
	identity := boundWireGuardIdentity(parsed.ipc)
	binding := networkFixture()
	inner := &ownedUDPBind{closeFail: true}
	var retainedBind *networkBind
	var retainedNetwork *boundNetwork
	route, err := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", binding,
		func(n *boundNetwork) (conn.Bind, error) {
			retainedNetwork = n
			retainedBind = &networkBind{inner: inner, network: n, fds: inner.fds}
			return retainedBind, nil
		})
	if route != nil || err != errRouteCleanup || !binding.unknown.Load() || inner.sends.Load() != 0 {
		t.Fatal("Uncertain constructor cleanup was accepted or sent peer traffic")
	}
	boundWireGuardOwners.Lock()
	owners := append([]quarantinedWireGuard(nil), boundWireGuardOwners.quarantined[identity]...)
	boundWireGuardOwners.Unlock()
	if len(owners) != 1 || owners[0].engine == nil || owners[0].bind != retainedBind || owners[0].network != retainedNetwork {
		t.Fatal("Constructor discarded the actual uncertain native owner")
	}
	// Later success of Close cannot upgrade the first failure or clear identity.
	inner.closeFail = false
	if retainedBind.Close() != errRouteCleanup {
		t.Fatal("A later close upgraded uncertain cleanup")
	}
	for _, endpoint := range []string{"outer.owned.test:51820", "other.owned.test:51821"} {
		next := networkFixture()
		called := false
		route, err := newWireGuardBound(fixtureConfig(client, server, endpoint), localUser, localPassword, localRealm, "origin.owned.test", next,
			func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
		if route != nil || err != errRouteCleanup || called || len(next.resolved()) != 0 || next.binds.Load() != 0 || !next.unknown.Load() {
			t.Fatal("Quarantined peer replacement reached new native or DNS work")
		}
	}
	otherClient, otherServer := syntheticKeys(t)
	called := false
	next := networkFixture()
	route, err = newWireGuardBound(fixtureConfig(otherClient, otherServer, "outer.owned.test:51820"), localUser, localPassword, localRealm, "origin.owned.test", next,
		func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
	if route != nil || err != errConfig || !called || next.unknown.Load() {
		t.Fatal("Unrelated peer was affected by private quarantine")
	}
}

func TestNetworkWireGuardConcurrentConstructorCannotRacePeerRollback(t *testing.T) {
	client, server := syntheticKeys(t)
	config := fixtureConfig(client, server, "outer.owned.test:51820")
	entered, release := make(chan struct{}), make(chan struct{})
	firstDone := make(chan error, 1)
	go func() {
		_, err := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", networkFixture(),
			func(*boundNetwork) (conn.Bind, error) { close(entered); <-release; return nil, errConfig })
		firstDone <- err
	}()
	awaitNetwork(t, entered)
	second := networkFixture()
	called := false
	_, secondErr := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", second,
		func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
	close(release)
	if awaitNetwork(t, firstDone) != errConfig || secondErr != errConnect || called || len(second.resolved()) != 0 || second.unknown.Load() {
		t.Fatal("Concurrent peer construction raced rollback or invented cleanup uncertainty")
	}
	_, err := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", networkFixture(),
		func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
	if err != errConfig || !called {
		t.Fatal("Confirmed constructor refusal left a permanent reservation")
	}
}

func TestNetworkWireGuardPublishedPeerReservedThroughHeldCloseAndCleanupVerdict(t *testing.T) {
	for _, refuse := range []bool{false, true} {
		name := "confirmed-close"
		if refuse {
			name = "unconfirmed-close"
		}
		t.Run(name, func(t *testing.T) {
			peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
			if err != nil {
				t.Fatal("Owned endpoint unavailable")
			}
			defer peer.Close()
			client, server := syntheticKeys(t)
			config := fixtureConfig(client, server, peer.LocalAddr().String())
			binding := networkFixture()
			var held *heldCloseWireGuardBind
			route, err := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", binding,
				func(n *boundNetwork) (conn.Bind, error) {
					inner := &ownedUDPBind{}
					held = &heldCloseWireGuardBind{Bind: &networkBind{inner: inner, network: n, fds: inner.fds}, entered: make(chan struct{}), release: make(chan struct{}), refuse: refuse}
					return held, nil
				})
			if err != nil {
				t.Fatal("Owned bound peer did not publish")
			}
			held.hold.Store(true)
			closed := make(chan error, 1)
			go func() { closed <- route.Close() }()
			// Release the native wait even if an assertion below fails.
			defer func() {
				select {
				case <-held.release:
				default:
					close(held.release)
				}
				route.Close()
			}()
			awaitNetwork(t, held.entered)
			replacement := networkFixture()
			called := false
			r, replacementErr := newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", replacement,
				func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
			if r != nil || replacementErr != errConnect || called || len(replacement.resolved()) != 0 || replacement.binds.Load() != 0 {
				t.Fatal("Replacement reached DNS or fd work before old native close settled")
			}
			otherClient, otherServer := syntheticKeys(t)
			_, otherErr := newWireGuardBound(fixtureConfig(otherClient, otherServer, peer.LocalAddr().String()), localUser, localPassword, localRealm, "origin.owned.test", networkFixture(),
				func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
			if otherErr != errConfig || !called {
				t.Fatal("Held close blocked an unrelated peer")
			}
			close(held.release)
			closeErr := awaitNetwork(t, closed)
			called = false
			_, replacementErr = newWireGuardBound(config, localUser, localPassword, localRealm, "origin.owned.test", networkFixture(),
				func(*boundNetwork) (conn.Bind, error) { called = true; return nil, errConfig })
			if refuse {
				if closeErr != errRouteCleanup || replacementErr != errRouteCleanup || called || !binding.unknown.Load() {
					t.Fatal("Unconfirmed close released its peer or failed to mark scope sticky")
				}
			} else if closeErr != nil || replacementErr != errConfig || !called || binding.unknown.Load() {
				t.Fatal("Fully confirmed cleanup did not release its peer")
			}
		})
	}
}
