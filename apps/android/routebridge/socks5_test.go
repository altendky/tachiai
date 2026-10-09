package routebridge

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

type socksFixtureOptions struct {
	auth       bool
	method     byte
	authStatus byte
	reply      []byte
	pause      string
	prefix     string
	tlsConfig  *tls.Config
}

type socksFixture struct {
	listener net.Listener
	requests chan string
	paused   chan struct{}
	ended    chan struct{}
}

// Owned wire-format server; no destination dial, provider, DNS or real secret.
func newSocksFixture(t *testing.T, options socksFixtureOptions) *socksFixture {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("Fixture listener failed")
	}
	f := &socksFixture{listener, make(chan string, 32), make(chan struct{}, 32), make(chan struct{}, 32)}
	var workers sync.WaitGroup
	var mu sync.Mutex
	sockets := make(map[net.Conn]struct{})
	failures := make(chan error, 32)
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			sockets[c] = struct{}{}
			mu.Unlock()
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer func() { c.Close(); mu.Lock(); delete(sockets, c); mu.Unlock(); f.ended <- struct{}{} }()
				c.SetDeadline(time.Now().Add(10 * time.Second))
				if err := f.serve(c, options); err != nil {
					failures <- err
				}
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		mu.Lock()
		for c := range sockets {
			c.Close()
		}
		mu.Unlock()
		workers.Wait()
		close(failures)
		for range failures {
			t.Error("Owned SOCKS fixture protocol mismatch")
		}
	})
	return f
}

func fixtureClosed(c net.Conn) error {
	var data [1]byte
	if n, err := c.Read(data[:]); n != 0 || err == nil {
		return errConnect // No CONNECT/payload may follow a rejection.
	}
	return nil
}

func (f *socksFixture) pause(c net.Conn, options socksFixtureOptions, phase string) (bool, error) {
	if options.pause != phase {
		return false, nil
	}
	f.paused <- struct{}{}
	return true, fixtureClosed(c)
}

func (f *socksFixture) serve(c net.Conn, options socksFixtureOptions) error {
	var header [4]byte
	if _, err := io.ReadFull(c, header[:2]); err != nil || header[0] != 5 {
		return errConnect
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return errConnect
	}
	expected := []byte{0}
	if options.auth {
		expected = []byte{0, 2}
	}
	if !bytes.Equal(methods, expected) {
		return errConnect
	}
	if paused, err := f.pause(c, options, "greeting"); paused {
		return err
	}
	// Two writes also exercise preservation when a reply is fragmented.
	if _, err := c.Write([]byte{5}); err != nil {
		return errConnect
	}
	if _, err := c.Write([]byte{options.method}); err != nil {
		return errConnect
	}
	wanted := byte(0)
	if options.auth {
		wanted = 2
	}
	if options.method != wanted {
		return fixtureClosed(c)
	}
	if options.auth {
		if _, err := io.ReadFull(c, header[:2]); err != nil || header[0] != 1 {
			return errConnect
		}
		user := make([]byte, int(header[1]))
		if _, err := io.ReadFull(c, user); err != nil {
			return errConnect
		}
		if _, err := io.ReadFull(c, header[:1]); err != nil {
			return errConnect
		}
		password := make([]byte, int(header[0]))
		if _, err := io.ReadFull(c, password); err != nil || string(user) != "fixture:user" || string(password) != "p@ss+word" {
			return errConnect
		}
		if paused, err := f.pause(c, options, "auth"); paused {
			return err
		}
		if _, err := c.Write([]byte{1, options.authStatus}); err != nil {
			return errConnect
		}
		if options.authStatus != 0 {
			return fixtureClosed(c)
		}
	}
	if _, err := io.ReadFull(c, header[:]); err != nil || !bytes.Equal(header[:], []byte{5, 1, 0, 3}) {
		return errConnect // Domain type 3, not a locally resolved IP.
	}
	if _, err := io.ReadFull(c, header[:1]); err != nil {
		return errConnect
	}
	destination := make([]byte, int(header[0])+2)
	if _, err := io.ReadFull(c, destination); err != nil {
		return errConnect
	}
	if destination[len(destination)-2] != 1 || destination[len(destination)-1] != 187 {
		return errConnect // 443, never a proxy endpoint or arbitrary port.
	}
	f.requests <- string(destination[:len(destination)-2]) + ":443"
	if paused, err := f.pause(c, options, "connect"); paused {
		return err
	}
	reply := options.reply
	if reply == nil {
		reply = []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}
	}
	if _, err := c.Write(reply); err != nil {
		return errConnect
	}
	if !bytes.Equal(reply, []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}) {
		return nil // Malformed/truncated replies close their own connection.
	}
	if options.tlsConfig != nil {
		origin := tls.Server(c, options.tlsConfig)
		if origin.Handshake() != nil {
			return nil // Rejected certificates never receive an HTTP request.
		}
		request, err := readHeaders(bufio.NewReader(origin))
		if err != nil || request.Host != "origin.invalid" || request.Header.Get("Proxy-Authorization") != "" ||
			strings.Contains(fmt.Sprint(request.Header), "fixture:user") || strings.Contains(fmt.Sprint(request.Header), "p@ss+word") {
			return errConnect
		}
		_, err = io.WriteString(origin, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
		return err
	}
	io.WriteString(c, options.prefix)
	io.Copy(c, c)
	return nil
}

func (f *socksFixture) config(auth bool) string {
	credentials := ""
	if auth {
		credentials = "fixture%3Auser:p%40ss%2Bword@"
	}
	return "socks5://" + credentials + f.listener.Addr().String()
}

func awaitSocksEvent(t *testing.T, events <-chan struct{}) {
	t.Helper()
	select {
	case <-events:
	case <-time.After(2 * time.Second):
		t.Fatal("SOCKS lifecycle event did not finish")
	}
}

func TestSocks5ConfigFailsClosed(t *testing.T) {
	for _, good := range []string{"socks5://proxy.example.test:1080", "socks5://127.0.0.1:1080/", "socks5://[2001:db8::1]:1080", "socks5://fixture%3Auser:p%40ss%2Bword@proxy.example.test:1080", "socks5://" + strings.Repeat("u", 255) + ":" + strings.Repeat("p", 255) + "@proxy.example.test:1080"} {
		if _, _, err := parseSocks5(good); err != nil {
			t.Fatal("Valid SOCKS fixture rejected")
		}
	}
	for _, bad := range []string{"http://proxy.example.test:1080", "socks5h://proxy.example.test:1080", "socks5://proxy.example.test", "socks5://proxy.example.test:0", "socks5://proxy.example.test:65536", "socks5://proxy.example.test:1080/path", "socks5://proxy.example.test:1080?", "socks5://proxy.example.test:1080#", "socks5://u@proxy.example.test:1080", "socks5://u:@proxy.example.test:1080", "socks5://:p@proxy.example.test:1080", "socks5://u:p:x@proxy.example.test:1080", "socks5://u:p+word@proxy.example.test:1080", "socks5://u:%00@proxy.example.test:1080", "socks5://u:%0A@proxy.example.test:1080", "socks5://u:%C0%AF@proxy.example.test:1080", "socks5://u:%E2%80%AE@proxy.example.test:1080", "socks5://u:%zz@proxy.example.test:1080", "socks5://999.1.1.1:1080", "socks5://[fe80::1%25eth0]:1080", "socks5://" + strings.Repeat("u", 256) + ":p@proxy.example.test:1080", strings.Repeat("x", headerLimit+1)} {
		if _, err := NewSocks5(bad, localUser, localPassword, localRealm, "origin.invalid"); err != errConfig {
			t.Fatal("Invalid SOCKS fixture accepted or error not redacted")
		}
	}
}

func TestSocks5AuthenticationHostnameForwardingAndBytes(t *testing.T) {
	for _, auth := range []bool{false, true} {
		t.Run(fmt.Sprint(auth), func(t *testing.T) {
			f := newSocksFixture(t, socksFixtureOptions{auth: auth, method: map[bool]byte{false: 0, true: 2}[auth], prefix: "opaque-prefix"})
			r, err := NewSocks5(f.config(auth), localUser, localPassword, localRealm, "origin.invalid")
			if err != nil {
				t.Fatal("SOCKS route failed")
			}
			defer r.Close()
			c, reader, status := connect(t, r, "origin.invalid:443", localAuth())
			defer c.Close()
			if status != 200 || <-f.requests != "origin.invalid:443" {
				t.Fatal("SOCKS destination was resolved locally or handshake failed")
			}
			prefix := make([]byte, len("opaque-prefix"))
			if _, err := io.ReadFull(reader, prefix); err != nil || string(prefix) != "opaque-prefix" {
				t.Fatal("SOCKS tunnel prefix lost")
			}
			io.WriteString(c, "opaque-provider-TLS")
			data := make([]byte, len("opaque-provider-TLS"))
			if _, err := io.ReadFull(reader, data); err != nil || string(data) != "opaque-provider-TLS" {
				t.Fatal("SOCKS tunnel bytes changed")
			}
			r.Close()
			awaitSocksEvent(t, f.ended)
		})
	}
}

func TestSocks5ProviderTlsStaysEndToEndAndRejectsBadCertificates(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("Owned TLS key failed")
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Owned fixture"},
		DNSNames: []string{"origin.invalid"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal("Owned TLS certificate failed")
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal("Owned TLS certificate parsing failed")
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	serverTLS := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	for _, test := range []struct {
		name, hostname string
		roots          *x509.CertPool
		success        bool
	}{
		{"trusted", "origin.invalid", roots, true}, {"wrong hostname", "other.invalid", roots, false},
		{"unknown CA", "origin.invalid", x509.NewCertPool(), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := newSocksFixture(t, socksFixtureOptions{auth: true, method: 2, tlsConfig: serverTLS})
			r, err := NewSocks5(f.config(true), localUser, localPassword, localRealm, "origin.invalid")
			if err != nil {
				t.Fatal("SOCKS route failed")
			}
			defer r.Close()
			c, _, status := connect(t, r, "origin.invalid:443", localAuth())
			defer c.Close()
			if status != 200 {
				t.Fatal("SOCKS TLS transport failed")
			}
			origin := tls.Client(c, &tls.Config{ServerName: test.hostname, RootCAs: test.roots, MinVersion: tls.VersionTLS12})
			defer origin.Close()
			err = origin.Handshake()
			if (err == nil) != test.success {
				t.Fatal("Origin TLS verification changed")
			}
			if test.success {
				io.WriteString(origin, "GET /fixture HTTP/1.1\r\nHost: origin.invalid\r\nAuthorization: Bearer synthetic-origin-token\r\n\r\n")
				response, err := http.ReadResponse(bufio.NewReader(origin), &http.Request{Method: "GET"})
				if err != nil || response.StatusCode != 200 {
					t.Fatal("Owned HTTPS origin failed")
				}
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				if err != nil || string(body) != "ok" {
					t.Fatal("Owned HTTPS body changed")
				}
			}
		})
	}
}

func TestSocks5RejectionsNeverFallback(t *testing.T) {
	for name, options := range map[string]socksFixtureOptions{
		"auth failure":           {auth: true, method: 2, authStatus: 1},
		"auth downgrade":         {auth: true, method: 0},
		"anonymous wrong method": {method: 2},
		"unsupported method":     {method: 1},
		"no methods":             {method: 255},
		"proxy refusal":          {reply: []byte{5, 5, 0, 1}},
		"wrong version":          {reply: []byte{4, 0, 0, 1}},
		"reserved field":         {reply: []byte{5, 0, 1, 1}},
		"wrong address type":     {reply: []byte{5, 0, 0, 9}},
		"truncated reply":        {reply: []byte{5, 0, 0, 1, 127}},
	} {
		t.Run(name, func(t *testing.T) {
			f := newSocksFixture(t, options)
			r, err := NewSocks5(f.config(options.auth), localUser, localPassword, localRealm, "origin.invalid")
			if err != nil {
				t.Fatal("SOCKS route failed")
			}
			defer r.Close()
			c, _, status := connect(t, r, "origin.invalid:443", localAuth())
			c.Close()
			if status != 502 {
				t.Fatal("Rejected SOCKS handshake did not fail closed")
			}
			awaitSocksEvent(t, f.ended)
		})
	}
}

func TestSocks5CloseCancelsEveryNegotiationPhase(t *testing.T) {
	for _, phase := range []string{"greeting", "auth", "connect"} {
		t.Run(phase, func(t *testing.T) {
			f := newSocksFixture(t, socksFixtureOptions{auth: true, method: 2, pause: phase})
			r, err := NewSocks5(f.config(true), localUser, localPassword, localRealm, "origin.invalid")
			if err != nil {
				t.Fatal("SOCKS route failed")
			}
			defer r.Close()
			c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()))
			if err != nil {
				t.Fatal("Local dial failed")
			}
			defer c.Close()
			io.WriteString(c, "CONNECT origin.invalid:443 HTTP/1.1\r\nHost: origin.invalid:443\r\n"+localAuth()+"\r\n")
			awaitSocksEvent(t, f.paused)
			done := make(chan struct{})
			go func() { r.Close(); r.Close(); close(done) }()
			awaitSocksEvent(t, done)
			awaitSocksEvent(t, f.ended)
			if socket, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()), time.Second); err == nil {
				socket.Close()
				t.Fatal("Closed SOCKS route still listening")
			}
		})
	}
}

func TestSocks5MethodGuardHandlesSplitReplies(t *testing.T) {
	for _, method := range []byte{0, 2} {
		for _, selected := range []byte{0, 1, 2, 255} {
			client, server := net.Pipe()
			guard := &socksMethodConn{Conn: client, method: method}
			done := make(chan struct{})
			go func() { server.Write([]byte{5}); server.Write([]byte{selected}); server.Close(); close(done) }()
			var reply [2]byte
			_, firstErr := io.ReadFull(guard, reply[:1])
			_, secondErr := io.ReadFull(guard, reply[1:])
			client.Close()
			awaitSocksEvent(t, done)
			if firstErr != nil || (secondErr == nil) != (method == selected) {
				t.Fatal("Split method selection was not enforced")
			}
		}
	}
}

func TestSocks5CancelledBootstrapNeverOpensProxySocket(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("Owned listener failed")
	}
	defer listener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	forward := &socksForward{}
	if c, err := forward.DialContext(ctx, "tcp", listener.Addr().String()); err != errConnect || c != nil {
		if c != nil {
			c.Close()
		}
		t.Fatal("Cancelled bootstrap connected or exposed its error")
	}
}

func TestSocks5RoutesRemainIsolatedAndRejectBeforeProxyDial(t *testing.T) {
	a := newSocksFixture(t, socksFixtureOptions{prefix: "route-a"})
	b := newSocksFixture(t, socksFixtureOptions{prefix: "route-b"})
	first, err := NewSocks5(a.config(false), localUser, localPassword, localRealm, "origin.invalid")
	if err != nil {
		t.Fatal("First route failed")
	}
	defer first.Close()
	second, err := NewSocks5(b.config(false), localUser, localPassword, localRealm, "origin.invalid")
	if err != nil {
		t.Fatal("Second route failed")
	}
	defer second.Close()
	for _, rejected := range []struct {
		target, auth string
		status       int
	}{
		{"origin.invalid:443", "", 407}, {"origin.invalid:80", localAuth(), 403},
		{"127.0.0.1:443", localAuth(), 403}, {"other.invalid:443", localAuth(), 403},
	} {
		c, _, status := connect(t, first, rejected.target, rejected.auth)
		c.Close()
		if status != rejected.status {
			t.Fatal("SOCKS changed loopback admission")
		}
	}
	if len(a.requests) != 0 {
		t.Fatal("Rejected destination reached SOCKS server")
	}
	c1, reader1, status1 := connect(t, first, "origin.invalid:443", localAuth())
	defer c1.Close()
	c2, reader2, status2 := connect(t, second, "origin.invalid:443", localAuth())
	defer c2.Close()
	if status1 != 200 || status2 != 200 || first.Port() == second.Port() {
		t.Fatal("Independent SOCKS routes failed")
	}
	for _, pair := range []struct {
		reader io.Reader
		prefix string
	}{{reader1, "route-a"}, {reader2, "route-b"}} {
		data := make([]byte, len(pair.prefix))
		if _, err := io.ReadFull(pair.reader, data); err != nil || string(data) != pair.prefix {
			t.Fatal("SOCKS routes crossed")
		}
	}
	first.Close()
	awaitSocksEvent(t, a.ended)
	io.WriteString(c2, "still-independent")
	data := make([]byte, len("still-independent"))
	if _, err := io.ReadFull(reader2, data); err != nil || string(data) != "still-independent" {
		t.Fatal("Closing one SOCKS route interrupted another")
	}
	second.Close()
	awaitSocksEvent(t, b.ended)
}

// The public upstream client must remain context-aware, including transport
// bootstrap. No environment-proxy or direct-destination dial path is selected.
var _ interface {
	DialContext(context.Context, string, string) (net.Conn, error)
} = (*socksForward)(nil)
