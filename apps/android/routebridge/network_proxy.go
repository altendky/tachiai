package routebridge

import (
	"bufio"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/proxy"
)

// NewSystemBound is the explicit supplied-Network variant. Old constructors do
// not acquire or consult this callback capability.
func NewSystemBound(username, password, realm, allowedHosts string, binding NetworkBinding) (*Route, error) {
	n, err := newBoundNetwork(binding)
	if err != nil {
		return nil, err
	}
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, n.dial, n.cleanup(nil))
	if err != nil {
		return nil, n.failure(err)
	}
	return n.finish(route), nil
}

// NewHttpProxyBound binds only the outer proxy connection. The CONNECT target
// and origin TLS remain opaque to this transport, as in NewHttpProxy.
func NewHttpProxyBound(config, username, password, realm, allowedHosts string, binding NetworkBinding) (*Route, error) {
	n, err := newBoundNetwork(binding)
	if err != nil {
		return nil, err
	}
	fail := func() (*Route, error) { return nil, n.failure(errConfig) }
	if len(config) > headerLimit {
		return fail()
	}
	u, err := url.Parse(config)
	if err != nil || u.Scheme != "http" || u.Hostname() == "" || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return fail()
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1 || port > 65535 {
		return fail()
	}
	auth := ""
	if u.User != nil {
		secret, ok := u.User.Password()
		user := u.User.Username()
		if !ok || user == "" || strings.ContainsAny(user+secret, "\r\n\x00") || strings.Contains(user, ":") {
			return fail()
		}
		auth = "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+secret))
	}
	endpoint := net.JoinHostPort(u.Hostname(), u.Port())
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, func(ctx context.Context, target string) (net.Conn, error) {
		connection, err := n.dial(ctx, endpoint)
		if err != nil {
			return nil, errConnect
		}
		stop := context.AfterFunc(ctx, func() { connection.Close() })
		ok := false
		defer func() {
			stop()
			if !ok {
				connection.Close()
			}
		}()
		if deadline, exists := ctx.Deadline(); exists {
			connection.SetDeadline(deadline)
		}
		header := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
		if auth != "" {
			header += "Proxy-Authorization: " + auth + "\r\n"
		}
		if _, err := io.WriteString(connection, header+"\r\n"); err != nil {
			return nil, errConnect
		}
		reader := bufio.NewReaderSize(connection, headerLimit+1)
		response, err := readResponse(reader)
		if err != nil || response.StatusCode != 200 || response.ContentLength > 0 || len(response.TransferEncoding) != 0 {
			return nil, errConnect
		}
		if !n.active(ctx) {
			return nil, errConnect
		}
		connection.SetDeadline(time.Time{})
		ok = true
		return &bufferedConn{Conn: connection, reader: reader}, nil
	}, n.cleanup(nil))
	if err != nil {
		return nil, n.failure(err)
	}
	return n.finish(route), nil
}

type boundSocksForward struct {
	network *boundNetwork
	method  byte
}

func (d *boundSocksForward) DialContext(ctx context.Context, _ string, address string) (net.Conn, error) {
	connection, err := d.network.dial(ctx, address)
	if err != nil {
		return nil, errConnect
	}
	return &socksMethodConn{Conn: connection, method: d.method}, nil
}
func (d *boundSocksForward) Dial(network, address string) (net.Conn, error) {
	return d.DialContext(context.Background(), network, address)
}

// NewSocks5Bound preserves remote destination DNS and the profile's selected
// authentication method; only outer proxy DNS/TCP uses NetworkBinding.
func NewSocks5Bound(config, username, password, realm, allowedHosts string, binding NetworkBinding) (*Route, error) {
	n, err := newBoundNetwork(binding)
	if err != nil {
		return nil, err
	}
	endpoint, auth, err := parseSocks5(config)
	if err != nil {
		return nil, n.failure(errConfig)
	}
	method := byte(0)
	if auth != nil {
		method = 2
	}
	dialer, err := proxy.SOCKS5("tcp", endpoint, auth, &boundSocksForward{network: n, method: method})
	if err != nil {
		return nil, n.failure(errConfig)
	}
	contextDialer, ok := dialer.(proxy.ContextDialer)
	if !ok {
		return nil, n.failure(errConfig)
	}
	route, err := newRouteWithCleanup(username, password, realm, allowedHosts, func(ctx context.Context, target string) (net.Conn, error) {
		connection, err := contextDialer.DialContext(ctx, "tcp", target)
		if err != nil {
			return nil, errConnect
		}
		if !n.active(ctx) {
			connection.Close()
			return nil, errConnect
		}
		return connection, nil
	}, n.cleanup(nil))
	if err != nil {
		return nil, n.failure(err)
	}
	return n.finish(route), nil
}
