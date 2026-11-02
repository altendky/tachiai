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
	parsed, err := parseWireGuard(config)
	if err != nil {
		return nil, n.failure(errConfig)
	}
	if _, err = parseHosts(allowedHosts); err != nil || !localCredentialPattern.MatchString(username) || !localCredentialPattern.MatchString(password) || !localCredentialPattern.MatchString(realm) {
		return nil, n.failure(errConfig)
	}
	identity := boundWireGuardIdentity(parsed.ipc)
	if err := reserveBoundWireGuard(identity); err != nil {
		if err == errRouteCleanup {
			n.unconfirmed()
		}
		return nil, n.failure(err)
	}
	releaseReservation := func() {
		boundWireGuardOwners.Lock()
		delete(boundWireGuardOwners.reserved, identity)
		boundWireGuardOwners.Unlock()
	}
	published := false
	defer func() {
		if !published {
			releaseReservation()
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
	engine := device.NewDevice(tun, bind, logger)
	closeEngine := func() error {
		engine.Close()
		if bind.Close() != nil {
			n.unconfirmed()
			quarantineBoundWireGuard(identity, engine, bind, n)
			return errRouteCleanup
		}
		return nil
	}
	rollback := func(cause error) (*Route, error) {
		n.stop()
		closeEngine()
		if !n.confirmed() {
			n.unconfirmed()
			quarantineBoundWireGuard(identity, engine, bind, n)
		}
		return nil, n.failure(cause)
	}
	if engine.IpcSet(parsed.ipc) != nil || !n.active(context.Background()) || engine.Up() != nil {
		return rollback(errConnect)
	}
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, func(ctx context.Context, target string) (net.Conn, error) {
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
	}, n.cleanup(closeEngine))
	if err != nil {
		return rollback(err)
	}
	n.finish(route)
	afterCleanup := route.afterCleanup
	route.afterCleanup = func() error {
		if err := afterCleanup(); err != nil {
			quarantineBoundWireGuard(identity, engine, bind, n)
			return err
		}
		// Keep the peer reserved throughout publication and native Close, worker
		// joins and the callback cleanup verdict. Only complete confirmed cleanup
		// permits a replacement owner for this identity.
		releaseReservation()
		return nil
	}
	published = true
	return route, nil
}
