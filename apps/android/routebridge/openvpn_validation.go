//go:build openvpn_validation && cgo

package routebridge

/*
#cgo CFLAGS: -I${SRCDIR}/native
#cgo LDFLAGS: -ltachiai_openvpn -lssl -lcrypto -lfmt -llz4
#cgo !android LDFLAGS: -lstdc++ -lpthread
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/build/openvpn-native/arm64-v8a/prefix/lib
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/build/openvpn-native/x86_64/prefix/lib
#cgo android LDFLAGS: -lc++_static -lc++abi -lunwind -ldl -lm -Wl,--exclude-libs,ALL
#include <stdlib.h>
#include <string.h>
#include "native/adapter.h"
*/
import "C"

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

// Native linkage is enabled by the Android build helper's explicit tag;
// untagged host tests retain the existing provider-free routing coverage.
type openVPNPacketTunnel struct {
	bridge     *packetBridge
	stopOnce   sync.Once
	stop, done chan struct{}
	closeErr   error
}

var errOpenVPNTLS = errors.New("OpenVPN TLS refused")
var errOpenVPNTimeout = errors.New("OpenVPN preparation timed out")
var openVPNUnconfirmed atomic.Bool
var retainedOpenVPNMutex sync.Mutex
var retainedOpenVPNClients []*C.tachiai_ovpn

func releaseOpenVPNClient(client *C.tachiai_ovpn) bool {
	if C.tachiai_ovpn_destroy(client) == 1 {
		return true
	}
	// Never free/detach an unfinished worker. Keep the opaque handle alive and
	// refuse further OpenVPN initialization until process shutdown.
	openVPNUnconfirmed.Store(true)
	retainedOpenVPNMutex.Lock()
	retainedOpenVPNClients = append(retainedOpenVPNClients, client)
	retainedOpenVPNMutex.Unlock()
	return false
}

// Fixed numeric result boundary consumed by the native Android protocol.
type OpenVPNPreparedResult struct {
	route *Route
	code  int
}

func (r *OpenVPNPreparedResult) GetRoute() *Route { return r.route }
func (r *OpenVPNPreparedResult) GetCode() int     { return r.code }

func openVPNFailureCode(err error) int {
	switch err {
	case nil:
		return 0
	case errConfig:
		return 1
	case errOpenVPNTLS:
		return 2
	case errClosed:
		return 4
	case errOpenVPNTimeout:
		return 5
	case errRouteCleanup:
		return 6
	default:
		return 3
	}
}

// NewOpenVPNPrepared blocks only on the worker that owns setup/cleanup. Its
// caller registers preparation.Cancel before invoking it; Cancel never joins.
func NewOpenVPNPrepared(content, username, password, realm, allowed string, preparation *RoutePreparation) *OpenVPNPreparedResult {
	ctx, err := preparation.begin()
	if err != nil {
		return &OpenVPNPreparedResult{code: openVPNFailureCode(err)}
	}
	if _, err := parseHosts(allowed); err != nil || !localCredentialPattern.MatchString(username) || !localCredentialPattern.MatchString(password) || !localCredentialPattern.MatchString(realm) {
		preparation.Cancel()
		return &OpenVPNPreparedResult{code: 1}
	}
	tunnel, err := newOpenVPNPacketTunnel(ctx, content)
	if err != nil {
		preparation.Cancel()
		return &OpenVPNPreparedResult{code: openVPNFailureCode(err)}
	}
	cleanup := func() error { preparation.Cancel(); return tunnel.Close() }
	route, err := newRouteWithCleanup(username, password, realm, allowed, func(ctx context.Context, target string) (net.Conn, error) {
		return tunnel.bridge.network.DialContext(ctx, "tcp", target)
	}, cleanup)
	if err != nil {
		if cleanup() != nil {
			err = errRouteCleanup
		}
		return &OpenVPNPreparedResult{code: openVPNFailureCode(err)}
	}
	if ctx.Err() != nil {
		if route.Close() != nil {
			return &OpenVPNPreparedResult{code: 6}
		}
		return &OpenVPNPreparedResult{code: 4}
	}
	return &OpenVPNPreparedResult{route: route}
}

func newOpenVPNPacketTunnel(ctx context.Context, content string) (result *openVPNPacketTunnel, failure error) {
	if ctx.Err() != nil {
		return nil, errClosed
	}
	if openVPNUnconfirmed.Load() {
		return nil, errRouteCleanup
	}
	if len(content) == 0 || len(content) > headerLimit {
		return nil, errConfig
	}
	// Native peers may extend their own authentication timers. Keep an
	// independent preparation deadline even for a Background caller.
	preparation, cancelPreparation := context.WithTimeout(ctx, 30*time.Second)
	defer cancelPreparation()
	copy := C.CBytes([]byte(content))
	client := C.tachiai_ovpn_create((*C.char)(copy), C.size_t(len(content)), 30)
	C.memset(copy, 0, C.size_t(len(content)))
	C.free(copy)
	if client == nil {
		return nil, errConfig
	}
	transferred := false
	defer func() {
		if !transferred {
			C.tachiai_ovpn_cancel(client)
			if !releaseOpenVPNClient(client) {
				failure = errRouteCleanup
			}
		}
	}()
	if preparation.Err() != nil {
		if ctx.Err() == nil {
			return nil, errOpenVPNTimeout
		}
		return nil, errClosed
	}
	if C.tachiai_ovpn_start(client) != C.TACHIAI_OVPN_CONNECTING {
		return nil, errConnect
	}
	for {
		if preparation.Err() != nil {
			if ctx.Err() == nil {
				return nil, errOpenVPNTimeout
			}
			return nil, errClosed
		}
		status := C.tachiai_ovpn_wait(client, 50)
		if status == C.TACHIAI_OVPN_CONNECTED {
			break
		}
		if status != C.TACHIAI_OVPN_CONNECTING {
			if C.tachiai_ovpn_failure_reason(client) == C.TACHIAI_OVPN_FAILURE_TLS {
				return nil, errOpenVPNTLS
			}
			if C.tachiai_ovpn_failure_reason(client) == C.TACHIAI_OVPN_FAILURE_TIMEOUT {
				return nil, errOpenVPNTimeout
			}
			return nil, errConnect
		}
	}
	var spec C.tachiai_ovpn_spec
	fd := C.tachiai_ovpn_take_packet_fd(client, &spec)
	if fd < 0 {
		return nil, errConnect
	}
	file := os.NewFile(uintptr(fd), "owned-native-packet-transport")
	connection, err := net.FileConn(file)
	file.Close() // Consume native's transferred FD; FileConn owns a duplicate.
	if err != nil {
		return nil, errConnect
	}
	packets, ok := connection.(*net.UnixConn)
	if !ok {
		connection.Close()
		return nil, errConnect
	}
	if spec.address_count < 1 || spec.address_count > 16 || spec.dns_count < 1 || spec.dns_count > 16 {
		packets.Close()
		return nil, errConnect
	}
	var addresses, dns []netip.Addr
	for index := 0; index < int(spec.address_count); index++ {
		address, err := netip.ParseAddr(C.GoString(&spec.addresses[index][0]))
		if err != nil {
			packets.Close()
			return nil, errConnect
		}
		addresses = append(addresses, address)
	}
	for index := 0; index < int(spec.dns_count); index++ {
		address, err := netip.ParseAddr(C.GoString(&spec.dns[index][0]))
		if err != nil {
			packets.Close()
			return nil, errConnect
		}
		dns = append(dns, address)
	}
	bridge, err := newPacketBridge(packets, addresses, dns, int(spec.mtu))
	if err != nil {
		return nil, errConnect
	}
	tunnel := &openVPNPacketTunnel{bridge: bridge, stop: make(chan struct{}), done: make(chan struct{})}
	transferred = true
	go func() {
		// Sole owner of client after preparation. No other thread can destroy
		// the handle while a native condition-variable wait is outstanding.
		defer func() {
			C.tachiai_ovpn_cancel(client)
			bridge.Close()
			if !releaseOpenVPNClient(client) {
				tunnel.closeErr = errRouteCleanup
			}
			close(tunnel.done)
		}()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tunnel.stop:
				return
			case <-bridge.done:
				return
			default:
			}
			if C.tachiai_ovpn_wait_change(client, C.TACHIAI_OVPN_CONNECTED, 50) != C.TACHIAI_OVPN_CONNECTED {
				return
			}
		}
	}()
	if preparation.Err() != nil {
		if tunnel.Close() != nil {
			return nil, errRouteCleanup
		}
		if ctx.Err() == nil {
			return nil, errOpenVPNTimeout
		}
		return nil, errClosed
	}
	return tunnel, nil
}

func (t *openVPNPacketTunnel) Close() error {
	t.stopOnce.Do(func() { close(t.stop) })
	<-t.done
	return t.closeErr
}
