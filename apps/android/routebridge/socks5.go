package routebridge

import (
	"context"
	"net"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/proxy"
)

var socksEndpointLabel = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`)
var socksPort = regexp.MustCompile(`^[0-9]{1,5}$`)

func socksEndpointHost(host string) bool {
	if ip, err := netip.ParseAddr(host); err == nil {
		return !ip.Is4In6() && ip.Zone() == ""
	}
	if len(host) == 0 || len(host) > 253 || strings.Trim(host, "0123456789.") == "" {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if !socksEndpointLabel.MatchString(label) {
			return false
		}
	}
	return true
}

func socksCredential(raw string) (string, error) {
	for i := 0; i < len(raw); i++ {
		b := raw[i]
		if b == '%' {
			if i+2 >= len(raw) {
				return "", errConfig
			}
			if _, err := strconv.ParseUint(raw[i+1:i+3], 16, 8); err != nil {
				return "", errConfig
			}
			i += 2
		} else if !(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || strings.ContainsRune("-._~", rune(b))) {
			return "", errConfig
		}
	}
	value, err := url.PathUnescape(raw) // '+' is never a space in URI user-info.
	if err != nil || len(value) < 1 || len(value) > 255 || !utf8.ValidString(value) {
		return "", errConfig
	}
	for _, r := range value {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return "", errConfig
		}
	}
	return value, nil
}

func parseSocks5(config string) (string, *proxy.Auth, error) {
	if len(config) == 0 || len(config) > headerLimit || !strings.HasPrefix(config, "socks5://") || strings.ContainsAny(config, "?#") {
		return "", nil, errConfig
	}
	u, err := url.Parse(config)
	if err != nil || u.Scheme != "socks5" || u.Opaque != "" || (u.EscapedPath() != "" && u.EscapedPath() != "/") {
		return "", nil, errConfig
	}
	host := strings.ToLower(u.Hostname())
	if _, _, err := net.SplitHostPort(u.Host); err != nil {
		return "", nil, errConfig // IPv6 authority must use brackets.
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1 || port > 65535 || !socksPort.MatchString(u.Port()) || !socksEndpointHost(host) {
		return "", nil, errConfig
	}
	var auth *proxy.Auth
	if u.User != nil {
		authority := strings.TrimPrefix(config, "socks5://")
		if strings.Count(authority, "@") != 1 {
			return "", nil, errConfig
		}
		raw, _, ok := strings.Cut(authority, "@")
		if !ok || strings.Count(raw, ":") != 1 {
			return "", nil, errConfig
		}
		rawUser, rawPassword, _ := strings.Cut(raw, ":")
		user, userErr := socksCredential(rawUser)
		password, passwordErr := socksCredential(rawPassword)
		if userErr != nil || passwordErr != nil {
			return "", nil, errConfig
		}
		auth = &proxy.Auth{User: user, Password: password}
	}
	return net.JoinHostPort(host, strconv.Itoa(port)), auth, nil
}

// The upstream client advertises no-auth even with credentials and does not
// check the selected method for anonymous sessions. Require the profile's
// method before it sends authentication or CONNECT; never accept a downgrade.
type socksMethodConn struct {
	net.Conn
	method byte
	reply  [2]byte
	read   int
}

func (c *socksMethodConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	for i := 0; i < n && c.read < len(c.reply); i++ {
		c.reply[c.read] = p[i]
		c.read++
	}
	if c.read == len(c.reply) && (c.reply[0] != 5 || c.reply[1] != c.method) {
		return 0, errConnect
	}
	return n, err
}

type socksForward struct {
	net.Dialer
	method byte
}

func (d *socksForward) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	c, err := d.Dialer.DialContext(ctx, network, address)
	if err != nil {
		return nil, errConnect
	}
	return &socksMethodConn{Conn: c, method: d.method}, nil
}

// NewSocks5 owns an independent TCP-only SOCKS5 route. Destination hostnames
// reach the configured proxy unchanged; only proxy bootstrap uses system DNS.
// RFC1929 credentials go solely to the proxy and are not encrypted by SOCKS5.
func NewSocks5(config, username, password, realm, allowedHosts string) (*Route, error) {
	endpoint, auth, err := parseSocks5(config)
	if err != nil {
		return nil, errConfig
	}
	method := byte(0)
	if auth != nil {
		method = 2
	}
	dialer, err := proxy.SOCKS5("tcp", endpoint, auth, &socksForward{method: method})
	if err != nil {
		return nil, errConfig
	}
	contextDialer, ok := dialer.(proxy.ContextDialer)
	if !ok {
		return nil, errConfig
	}
	return newRoute(username, password, realm, allowedHosts, func(ctx context.Context, target string) (net.Conn, error) {
		connection, err := contextDialer.DialContext(ctx, "tcp", target)
		if err != nil {
			return nil, errConnect // Upstream errors may contain endpoint metadata.
		}
		return connection, nil
	}, nil)
}
