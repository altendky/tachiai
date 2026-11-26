package routebridge

import (
	"context"
	"crypto/sha256"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// A failed native rollback has no returned Route owner. Keep its actual objects
// strongly reachable for the process lifetime; a later constructor must not
// replace that peer while its former native resources remain uncertain.
// Identity and resources are private RAM only, never receipts or log fields.
type quarantinedWireGuard struct {
	engine  *device.Device
	bind    conn.Bind
	network *boundNetwork
}

var boundWireGuardOwners = struct {
	sync.Mutex
	reserved    map[[32]byte]bool
	quarantined map[[32]byte][]quarantinedWireGuard
}{reserved: make(map[[32]byte]bool), quarantined: make(map[[32]byte][]quarantinedWireGuard)}

func boundWireGuardIdentity(ipc string) [32]byte {
	var identity strings.Builder
	for _, line := range strings.Split(ipc, "\n") {
		if strings.HasPrefix(line, "private_key=") || strings.HasPrefix(line, "public_key=") {
			identity.WriteString(line)
			identity.WriteByte('\n')
		}
	}
	return sha256.Sum256([]byte(identity.String()))
}

func reserveBoundWireGuard(identity [32]byte) error {
	boundWireGuardOwners.Lock()
	defer boundWireGuardOwners.Unlock()
	if _, found := boundWireGuardOwners.quarantined[identity]; found {
		return errRouteCleanup
	}
	if boundWireGuardOwners.reserved[identity] {
		return errConnect
	}
	boundWireGuardOwners.reserved[identity] = true
	return nil
}

func quarantineBoundWireGuard(identity [32]byte, engine *device.Device, bind conn.Bind, network *boundNetwork) {
	boundWireGuardOwners.Lock()
	defer boundWireGuardOwners.Unlock()
	// Constructor reservations prevent a second constructor from racing a
	// failed rollback. Retain all already published owners if more than one
	// independently owned Route for this peer later reports uncertainty.
	for _, owner := range boundWireGuardOwners.quarantined[identity] {
		if owner.engine == engine {
			return
		}
	}
	boundWireGuardOwners.quarantined[identity] = append(boundWireGuardOwners.quarantined[identity], quarantinedWireGuard{engine, bind, network})
}

// NewWireGuardBound confines external endpoint DNS and UDP to the exact supplied
// Network. Inner destination TCP/DNS remain inside the configured userspace peer.
func NewWireGuardBound(config, username, password, realm, allowedHosts string, binding NetworkBinding) (*Route, error) {
	return newWireGuardBound(config, username, password, realm, allowedHosts, binding, defaultNetworkBind)
}
func newWireGuardBound(config, username, password, realm, allowedHosts string, binding NetworkBinding,
	makeBind func(*boundNetwork) (conn.Bind, error)) (*Route, error) {
	n, err := newBoundNetwork(binding)
	if err != nil {
		return nil, err
	}
	if _, err = parseHosts(allowedHosts); err != nil || !localCredentialPattern.MatchString(username) || !localCredentialPattern.MatchString(password) || !localCredentialPattern.MatchString(realm) {
		return nil, n.failure(errConfig)
	}
	peer, err := startBoundWireGuard(config, n, makeBind)
	if err != nil {
		return nil, err
	}
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, peer.dial, n.cleanup(peer.close))
	if err != nil {
		return nil, peer.rollback(err)
	}
	n.finish(route)
	afterCleanup := route.afterCleanup
	route.afterCleanup = func() error { return peer.verdict(afterCleanup()) }
	return route, nil
}

// NewWireGuardBoundEngine is NewWireGuardBound's device lent to several leases
// (#275 stage 5): one renewal job's device, whose outer endpoint DNS and UDP
// use only the job's Network. The peer stays reserved until Close confirms
// cleanup; an unconfirmed close quarantines it like a bound Route.
func NewWireGuardBoundEngine(config string, binding NetworkBinding) (*Engine, error) {
	return newWireGuardBoundEngine(config, binding, defaultNetworkBind)
}
func newWireGuardBoundEngine(config string, binding NetworkBinding, makeBind func(*boundNetwork) (conn.Bind, error)) (*Engine, error) {
	n, err := newBoundNetwork(binding)
	if err != nil {
		return nil, err
	}
	peer, err := startBoundWireGuard(config, n, makeBind)
	if err != nil {
		return nil, err
	}
	cleanup := n.cleanup(peer.close)
	// Engine.Close closes every lease, joining its workers, before this runs.
	return newEngine(peer.dial, func() error {
		err := cleanup()
		if err == nil && !n.confirmed() {
			n.unconfirmed()
			err = errRouteCleanup
		}
		return peer.verdict(err)
	}), nil
}

// One started bound device, reserved for its peer identity until its owner
// reports a cleanup verdict.
type boundWireGuardPeer struct {
	identity [32]byte
	network  *boundNetwork
	engine   *device.Device
	bind     conn.Bind
	dial     dialFunc
}

func (p *boundWireGuardPeer) release() {
	boundWireGuardOwners.Lock()
	delete(boundWireGuardOwners.reserved, p.identity)
	boundWireGuardOwners.Unlock()
}

func (p *boundWireGuardPeer) close() error {
	p.engine.Close()
	if p.bind.Close() != nil {
		p.network.unconfirmed()
		quarantineBoundWireGuard(p.identity, p.engine, p.bind, p.network)
		return errRouteCleanup
	}
	return nil
}

// Before publication: close what started, then release the reservation (a
// quarantine still refuses the peer).
func (p *boundWireGuardPeer) rollback(cause error) error {
	p.network.stop()
	p.close()
	if !p.network.confirmed() {
		p.network.unconfirmed()
		quarantineBoundWireGuard(p.identity, p.engine, p.bind, p.network)
	}
	err := p.network.failure(cause)
	p.release()
	return err
}

// Keep the peer reserved throughout publication and native Close, worker joins
// and the callback cleanup verdict. Only complete confirmed cleanup permits a
// replacement owner for this identity.
func (p *boundWireGuardPeer) verdict(err error) error {
	if err != nil {
		quarantineBoundWireGuard(p.identity, p.engine, p.bind, p.network)
		return err
	}
	p.release()
	return nil
}

func startBoundWireGuard(config string, n *boundNetwork, makeBind func(*boundNetwork) (conn.Bind, error)) (*boundWireGuardPeer, error) {
	parsed, err := parseWireGuard(config)
	if err != nil {
		return nil, n.failure(errConfig)
	}
	identity := boundWireGuardIdentity(parsed.ipc)
	if err := reserveBoundWireGuard(identity); err != nil {
		if err == errRouteCleanup {
			n.unconfirmed()
		}
		return nil, n.failure(err)
	}
	peer := &boundWireGuardPeer{identity: identity, network: n}
	started := false
	defer func() {
		if !started {
			peer.release()
		}
	}()
	bind, err := makeBind(n)
	if err != nil {
		return nil, n.failure(errConfig)
	}
	host, port, _ := net.SplitHostPort(parsed.endpoint)
	if _, err := netip.ParseAddr(host); err != nil {
		ctx, cancel := context.WithTimeout(n.ctx, 15*time.Second)
		addresses, err := n.resolve(ctx, host)
		cancel()
		if err != nil || len(addresses) == 0 {
			return nil, n.failure(errConnect)
		}
		endpoint := net.JoinHostPort(addresses[0].String(), port)
		parsed.ipc = strings.Replace(parsed.ipc, "endpoint="+parsed.endpoint+"\n", "endpoint="+endpoint+"\n", 1)
	}
	if !n.active(context.Background()) {
		return nil, n.failure(errClosed)
	}
	tun, network, err := netstack.CreateNetTUN(parsed.addresses, parsed.dns, parsed.mtu)
	if err != nil {
		return nil, n.failure(errConnect)
	}
	logger := device.NewLogger(device.LogLevelSilent, "")
	logger.Verbosef = func(string, ...any) {}
	logger.Errorf = func(string, ...any) {}
	peer.engine = device.NewDevice(tun, bind, logger)
	peer.bind = bind
	if peer.engine.IpcSet(parsed.ipc) != nil || !n.active(context.Background()) || peer.engine.Up() != nil {
		return nil, peer.rollback(errConnect)
	}
	peer.dial = func(ctx context.Context, target string) (net.Conn, error) {
		if !n.active(ctx) {
			return nil, errConnect
		}
		connection, err := network.DialContext(ctx, "tcp", target)
		if err != nil {
			return nil, errConnect
		}
		if !n.active(ctx) {
			connection.Close()
			return nil, errConnect
		}
		return connection, nil
	}
	started = true
	return peer, nil
}
