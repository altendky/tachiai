//go:build openconnect_validation && cgo

package routebridge

/*
#cgo LDFLAGS: -ltachiai_openconnect
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/build/openconnect-native/arm64-v8a/prefix/lib
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/build/openconnect-native/x86_64/prefix/lib
#cgo android LDFLAGS: -lopenconnect -lgnutls -lnettle -lhogweed -lgmp -lxml2 -lz
#include <stdlib.h>
#include <string.h>
#include "../openconnect-fixture/adapter.h"
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
	"unsafe"
)

// Native linkage is enabled by the Android build helper's explicit tag.
type openConnectProfile struct {
	endpoint, ca, certificate, key, bootstrap string
}

type openConnectPacketTunnel struct {
	bridge                 *packetBridge
	stop, done, nativeDone chan struct{}
	stopOnce               sync.Once
	mu                     sync.Mutex
	cleanupError           error
}

var errOpenConnectTimeout = errors.New("OpenConnect preparation timed out")
var openConnectUnconfirmed atomic.Bool
var retainedOpenConnectMutex sync.Mutex
var retainedOpenConnectClients []*C.struct_toc_session

// An unjoined or undestroyed handle remains allocated until process exit. No
// other goroutine may access it, and further OpenConnect setup fails closed.
func retainOpenConnectClient(client *C.struct_toc_session) {
	openConnectUnconfirmed.Store(true)
	retainedOpenConnectMutex.Lock()
	retainedOpenConnectClients = append(retainedOpenConnectClients, client)
	retainedOpenConnectMutex.Unlock()
}

// Fixed Android result codes: 0 ready, 1 configuration, 2 reserved for a future
// TLS-specific native reason, 3 connection, 4 cancellation, 5 timeout, 6 cleanup.
// The current opaque native ABI does not distinguish TLS/auth/connect failures.
type OpenConnectPreparedResult struct {
	route *Route
	code  int
}

func (r *OpenConnectPreparedResult) GetRoute() *Route { return r.route }
func (r *OpenConnectPreparedResult) GetCode() int     { return r.code }

func openConnectFailureCode(err error) int {
	switch err {
	case nil:
		return 0
	case errConfig:
		return 1
	case errClosed:
		return 4
	case errOpenConnectTimeout:
		return 5
	case errRouteCleanup:
		return 6
	default:
		return 3
	}
}

// The caller registers preparation.Cancel before this blocking factory. Cancel
// only signals context; the worker owns native stop/join/destruction throughout.
func NewOpenConnectPrepared(endpoint, bootstrap, ca, certificate, key, username, password, realm, allowed string, preparation *RoutePreparation) *OpenConnectPreparedResult {
	ctx, err := preparation.begin()
	if err != nil {
		return &OpenConnectPreparedResult{code: openConnectFailureCode(err)}
	}
	if _, err := parseHosts(allowed); err != nil || !localCredentialPattern.MatchString(username) || !localCredentialPattern.MatchString(password) || !localCredentialPattern.MatchString(realm) {
		preparation.Cancel()
		return &OpenConnectPreparedResult{code: 1}
	}
	tunnel, err := newOpenConnectPacketTunnel(ctx, openConnectProfile{endpoint: endpoint, bootstrap: bootstrap, ca: ca, certificate: certificate, key: key})
	if err != nil {
		preparation.Cancel()
		return &OpenConnectPreparedResult{code: openConnectFailureCode(err)}
	}
	cleanup := func() error { preparation.Cancel(); return tunnel.Close() }
	route, err := newRouteWithCleanup(username, password, realm, allowed, func(ctx context.Context, target string) (net.Conn, error) {
		return tunnel.bridge.network.DialContext(ctx, "tcp", target)
	}, cleanup)
	if err != nil {
		if cleanup() != nil {
			err = errRouteCleanup
		}
		return &OpenConnectPreparedResult{code: openConnectFailureCode(err)}
	}
	if ctx.Err() != nil {
		if route.Close() != nil {
			return &OpenConnectPreparedResult{code: 6}
		}
		return &OpenConnectPreparedResult{code: openConnectFailureCode(openConnectContextError(ctx))}
	}
	return &OpenConnectPreparedResult{route: route}
}

func openConnectContextError(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return errOpenConnectTimeout
	}
	return errClosed
}

func newOpenConnectPrepared(p *RoutePreparation, profile openConnectProfile) (*openConnectPacketTunnel, error) {
	ctx, err := p.begin()
	if err != nil {
		return nil, err
	}
	return newOpenConnectPacketTunnel(ctx, profile)
}

func newOpenConnectPacketTunnel(ctx context.Context, profile openConnectProfile) (*openConnectPacketTunnel, error) {
	if ctx.Err() != nil {
		return nil, openConnectContextError(ctx)
	}
	if openConnectUnconfirmed.Load() {
		return nil, errRouteCleanup
	}
	if len(profile.bootstrap) == 0 || len(profile.bootstrap) > 15 {
		return nil, errConfig
	}
	if address, err := netip.ParseAddr(profile.bootstrap); err != nil || !address.Is4() {
		return nil, errConfig
	}
	parts := []string{profile.endpoint, profile.ca, profile.certificate, profile.key}
	total := 0
	for _, value := range parts {
		if len(value) == 0 || len(value) > headerLimit-total {
			return nil, errConfig
		}
		total += len(value)
	}
	preparation, cancelPreparation := context.WithTimeout(ctx, 30*time.Second)
	defer cancelPreparation()
	var nativeProfile C.struct_toc_profile
	fields := []*C.struct_toc_bytes{&nativeProfile.endpoint, &nativeProfile.ca_pem, &nativeProfile.client_pem, &nativeProfile.key_pem}
	for index, part := range parts {
		copy := C.CBytes([]byte(part))
		fields[index].data = copy
		fields[index].size = C.size_t(len(part))
		defer func(data unsafe.Pointer, size int) { C.memset(data, 0, C.size_t(size)); C.free(data) }(copy, len(part))
	}
	bootstrap := C.CString(profile.bootstrap)
	defer C.free(unsafe.Pointer(bootstrap))
	nativeProfile.bootstrap_ipv4 = bootstrap
	var client *C.struct_toc_session
	if preparation.Err() != nil {
		return nil, openConnectContextError(preparation)
	}
	if openConnectUnconfirmed.Load() {
		return nil, errRouteCleanup
	}
	if status := C.toc_create(&nativeProfile, &client); status != C.TOC_OK {
		if preparation.Err() != nil {
			return nil, openConnectContextError(preparation)
		}
		if status == C.TOC_INVALID {
			return nil, errConfig
		}
		return nil, errConnect
	}
	// The worker is the sole owner from here, including failures before start.
	tunnel := &openConnectPacketTunnel{stop: make(chan struct{}), done: make(chan struct{}), nativeDone: make(chan struct{})}
	type prepared struct {
		bridge *packetBridge
		err    error
	}
	ready := make(chan prepared, 1)
	go func() {
		var bridge *packetBridge
		var packets *net.UnixConn
		reported := false
		report := func(err error) {
			if !reported {
				ready <- prepared{bridge, err}
				reported = true
			}
		}
		defer func() {
			C.toc_cancel(client)
			if bridge != nil {
				bridge.Close()
			} else if packets != nil {
				packets.Close()
			}
			// Never free an active worker or report a timeout as cleanup success.
			// Retain the opaque handle and permanently refuse further setup.
			if C.toc_join(client, 5000) != C.TOC_OK {
				tunnel.mu.Lock()
				tunnel.cleanupError = errRouteCleanup
				tunnel.mu.Unlock()
				retainOpenConnectClient(client)
				close(tunnel.done)
				return
			}
			if C.toc_destroy(client) != C.TOC_OK {
				tunnel.mu.Lock()
				tunnel.cleanupError = errRouteCleanup
				tunnel.mu.Unlock()
				retainOpenConnectClient(client)
				close(tunnel.done)
				return // Destruction unconfirmed: keep nativeDone open.
			}
			close(tunnel.done)
			close(tunnel.nativeDone)
		}()
		if preparation.Err() != nil {
			report(openConnectContextError(preparation))
			return
		}
		var fd C.int
		if C.toc_take_packet_fd(client, &fd) != C.TOC_OK {
			report(errConnect)
			return
		}
		file := os.NewFile(uintptr(fd), "owned-openconnect-packet-transport")
		connection, err := net.FileConn(file)
		file.Close() // FileConn owns its duplicate; original transferred FD consumed.
		if err != nil {
			report(errConnect)
			return
		}
		var ok bool
		packets, ok = connection.(*net.UnixConn)
		if !ok {
			connection.Close()
			report(errConnect)
			return
		}
		if preparation.Err() != nil {
			report(openConnectContextError(preparation))
			return
		}
		if openConnectUnconfirmed.Load() {
			report(errRouteCleanup)
			return
		}
		if C.toc_start(client) != C.TOC_OK {
			report(errConnect)
			return
		}
		var spec C.struct_toc_snapshot
		for {
			if preparation.Err() != nil {
				report(openConnectContextError(preparation))
				return
			}
			status := C.toc_wait(client, 50, &spec)
			if status == C.TOC_READY {
				break
			}
			if status != C.TOC_PENDING && status != C.TOC_TIMEOUT {
				if preparation.Err() != nil {
					report(openConnectContextError(preparation))
					return
				}
				if status == C.TOC_CANCELLED {
					report(errClosed)
					return
				}
				report(errConnect)
				return
			}
		}
		if spec.dns_count < 1 || spec.dns_count > 3 {
			report(errConnect)
			return
		}
		// Snapshot integer fields carry raw network-order bytes from inet_pton.
		address := func(value *C.uint32_t) netip.Addr {
			bytes := unsafe.Slice((*byte)(unsafe.Pointer(value)), 4)
			return netip.AddrFrom4([4]byte{bytes[0], bytes[1], bytes[2], bytes[3]})
		}
		dns := make([]netip.Addr, int(spec.dns_count))
		for index := range dns {
			dns[index] = address(&spec.dns[index])
		}
		bridge, err = newPacketBridge(packets, []netip.Addr{address(&spec.ipv4)}, dns, int(spec.mtu))
		packets = nil // newPacketBridge consumes the socket on success or failure.
		if err != nil {
			report(errConnect)
			return
		}
		if preparation.Err() != nil {
			report(openConnectContextError(preparation))
			return
		}
		report(nil)
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
			// A real blocking native-finished condition wait. toc_wait would
			// return READY immediately and busy spin throughout active playback.
			status := C.toc_join(client, 100)
			if status == C.TOC_OK {
				return
			}
			if status != C.TOC_TIMEOUT {
				return
			}
		}
	}()
	select {
	case result := <-ready:
		if result.err != nil {
			if tunnel.Close() != nil {
				return nil, errRouteCleanup
			}
			if preparation.Err() != nil {
				return nil, openConnectContextError(preparation)
			}
			return nil, result.err
		}
		tunnel.bridge = result.bridge
		if preparation.Err() != nil {
			if tunnel.Close() != nil {
				return nil, errRouteCleanup
			}
			return nil, openConnectContextError(preparation)
		}
		return tunnel, nil
	case <-preparation.Done():
		if tunnel.Close() != nil {
			return nil, errRouteCleanup
		}
		return nil, openConnectContextError(preparation)
	}
}

func (t *openConnectPacketTunnel) Close() error {
	t.stopOnce.Do(func() { close(t.stop) })
	<-t.done
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cleanupError
}
