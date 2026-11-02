package routebridge

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// NetworkBinding is an operation-owned Android Network capability. Borrowed fd
// integers must not escape BindSocket. Resolve returns only newline-separated
// numeric addresses. Cancel is prompt; cleanup requires actual callback work to
// finish, rather than cancellation of a waiting caller. IsActive is a pure
// original-deadline/currentness check. Callbacks must not synchronously reenter
// this Route's Close or Bind lifecycle; the owner closes it separately. Use one
// scope per independently owned Route, because Route.Close cancels its scope.
// ReportCleanupUnconfirmed promptly retires that scope and permanently refuses
// its cleanup verdict; it must not call back into the Go lifecycle.
type NetworkBinding interface {
	BindSocket(fd int32) error
	Resolve(host string, timeoutMillis int64) (string, error)
	Cancel()
	IsActive() bool
	IsCleanupConfirmed() bool
	ReportCleanupUnconfirmed()
}

type boundNetwork struct {
	binding         NetworkBinding
	ctx             context.Context
	cancel          context.CancelFunc
	retired         atomic.Bool
	callbackFailure atomic.Bool
	cleanupUnknown  atomic.Bool
}

func newBoundNetwork(binding NetworkBinding) (*boundNetwork, error) {
	if binding == nil {
		return nil, errConfig
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &boundNetwork{binding: binding, ctx: ctx, cancel: cancel}
	if !n.active(ctx) {
		n.stop()
		return nil, n.failure(errClosed)
	}
	return n, nil
}

func (n *boundNetwork) active(ctx context.Context) (active bool) {
	defer func() {
		if recover() != nil {
			n.callbackFailed()
			active = false
		}
	}()
	if n.callbackFailure.Load() || n.cleanupUnknown.Load() || n.retired.Load() || n.ctx.Err() != nil || ctx.Err() != nil {
		return false
	}
	return n.binding.IsActive() && !n.callbackFailure.Load() && !n.cleanupUnknown.Load() && !n.retired.Load() && n.ctx.Err() == nil && ctx.Err() == nil
}
func (n *boundNetwork) callbackFailed() {
	n.callbackFailure.Store(true)
	n.unconfirmed()
}
func (n *boundNetwork) unconfirmed() {
	n.retired.Store(true)
	n.cancel()
	if n.cleanupUnknown.Swap(true) {
		return
	}
	defer func() {
		if recover() != nil {
			n.callbackFailure.Store(true)
		}
	}()
	n.binding.ReportCleanupUnconfirmed()
}
func (n *boundNetwork) stop() {
	n.retired.Store(true)
	n.cancel()
	func() {
		defer func() {
			if recover() != nil {
				n.callbackFailed()
			}
		}()
		n.binding.Cancel()
	}()
}
func (n *boundNetwork) confirmed() (confirmed bool) {
	defer func() {
		if recover() != nil {
			n.callbackFailed()
			confirmed = false
		}
	}()
	return !n.callbackFailure.Load() && !n.cleanupUnknown.Load() && n.binding.IsCleanupConfirmed()
}
func (n *boundNetwork) failure(cause error) error {
	n.stop()
	if !n.confirmed() {
		n.unconfirmed()
		return errRouteCleanup
	}
	return cause
}
func (n *boundNetwork) cleanup(extra func() error) func() error {
	return func() error {
		n.stop()
		if extra != nil && extra() != nil {
			n.unconfirmed()
			return errRouteCleanup
		}
		return nil
	}
}
func (n *boundNetwork) finish(route *Route) *Route {
	// Installed before the constructor publishes the Route to any caller.
	route.afterCleanup = func() error {
		if !n.confirmed() {
			n.unconfirmed()
			return errRouteCleanup
		}
		return nil
	}
	return route
}
func (n *boundNetwork) bind(ctx context.Context, fd int) (err error) {
	defer func() {
		if recover() != nil {
			n.callbackFailed()
			err = errConnect
		}
	}()
	if fd < 0 || int64(fd) > 2147483647 || !n.active(ctx) {
		return errClosed
	}
	if n.binding.BindSocket(int32(fd)) != nil {
		return errConnect
	}
	if !n.active(ctx) {
		return errClosed
	}
	return nil
}

func numericAddresses(raw string) ([]netip.Addr, error) {
	if len(raw) == 0 || len(raw) > 4096 {
		return nil, errConnect
	}
	lines := strings.Split(strings.TrimSpace(raw), "\n")
	if len(lines) == 0 || len(lines) > 32 {
		return nil, errConnect
	}
	var result []netip.Addr
	seen := make(map[netip.Addr]bool)
	for _, line := range lines {
		ip, err := netip.ParseAddr(strings.TrimSpace(line))
		if err != nil || ip.Zone() != "" || ip.IsUnspecified() || ip.IsMulticast() {
			return nil, errConnect
		}
		ip = ip.Unmap()
		if !seen[ip] {
			seen[ip] = true
			result = append(result, ip)
		}
	}
	return result, nil
}
func (n *boundNetwork) resolve(ctx context.Context, host string) (addresses []netip.Addr, err error) {
	defer func() {
		if recover() != nil {
			n.callbackFailed()
			addresses = nil
			err = errConnect
		}
	}()
	if !n.active(ctx) {
		return nil, errClosed
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return numericAddresses(ip.String())
	}
	if host == "" || len(host) > 253 || strings.ContainsAny(host, "\x00\r\n\t /\\?#@:") {
		return nil, errConnect
	}
	wait := 15 * time.Second
	if end, ok := ctx.Deadline(); ok {
		wait = min(wait, time.Until(end))
	}
	if wait < time.Millisecond {
		return nil, errClosed
	}
	raw, callErr := n.binding.Resolve(host, int64(wait/time.Millisecond))
	if !n.active(ctx) {
		return nil, errClosed
	}
	if callErr != nil {
		return nil, errConnect
	}
	return numericAddresses(raw)
}
func (n *boundNetwork) dial(ctx context.Context, target string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return nil, errConnect
	}
	parsed, err := strconv.Atoi(port)
	if err != nil || parsed < 1 || parsed > 65535 {
		return nil, errConnect
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(n.ctx, cancel)
	defer stop()
	ips, err := n.resolve(ctx, host)
	if err != nil {
		return nil, errConnect
	}
	var bindFailure error
	dialer := net.Dialer{ControlContext: func(controlCtx context.Context, _ string, _ string, raw syscall.RawConn) error {
		if err := raw.Control(func(fd uintptr) { bindFailure = n.bind(controlCtx, int(fd)) }); err != nil {
			bindFailure = errConnect
		}
		return bindFailure
	}}
	for _, ip := range ips {
		if !n.active(ctx) {
			return nil, errConnect
		}
		// Numeric-only input prevents net.Dialer from consulting ambient DNS.
		connection, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		if bindFailure != nil {
			if connection != nil {
				connection.Close()
			}
			return nil, errConnect
		}
		if err != nil {
			continue
		}
		if !n.active(ctx) {
			connection.Close()
			return nil, errConnect
		}
		return &boundConnection{Conn: connection, network: n}, nil
	}
	return nil, errConnect
}

type boundConnection struct {
	net.Conn
	network *boundNetwork
}

func (c *boundConnection) Read(p []byte) (int, error) {
	if !c.network.active(context.Background()) {
		return 0, errClosed
	}
	n, err := c.Conn.Read(p)
	if !c.network.active(context.Background()) {
		return 0, errClosed
	}
	return n, err
}
func (c *boundConnection) Write(p []byte) (int, error) {
	if !c.network.active(context.Background()) {
		return 0, errClosed
	}
	return c.Conn.Write(p)
}
