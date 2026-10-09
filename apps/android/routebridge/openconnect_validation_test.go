//go:build openconnect_validation && cgo

package routebridge

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type ocIdentity struct {
	certificate     tls.Certificate
	certPEM, keyPEM string
	parsed          *x509.Certificate
	key             *ecdsa.PrivateKey
}

func ocIdentityNew(t *testing.T, ca *ocIdentity, serial int64, name string, client bool) ocIdentity {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("Owned fixture key generation")
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(serial), Subject: pkix.Name{CommonName: "owned-openconnect"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), BasicConstraintsValid: true, KeyUsage: x509.KeyUsageDigitalSignature}
	if ca == nil {
		cert.IsCA = true
		cert.KeyUsage |= x509.KeyUsageCertSign
	} else if client {
		cert.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}
	} else {
		cert.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}
		cert.DNSNames = []string{name}
	}
	parent, parentKey := cert, key
	if ca != nil {
		parent, parentKey = ca.parsed, ca.key
	}
	der, err := x509.CreateCertificate(rand.Reader, cert, parent, &key.PublicKey, parentKey)
	if err != nil {
		t.Fatal("Owned fixture certificate signing")
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal("Owned fixture certificate parsing")
	}
	secret, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal("Owned fixture PEM serialization")
	}
	certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: secret}))
	pair, err := tls.X509KeyPair([]byte(certPEM), []byte(keyPEM))
	if err != nil {
		t.Fatal("Owned fixture TLS identity")
	}
	return ocIdentity{pair, certPEM, keyPEM, parsed, key}
}

type ocGateway struct {
	profile          openConnectProfile
	roots            *x509.CertPool
	listener         net.Listener
	network          *packetNetwork
	udp              net.PacketConn
	origin           *http.Server
	mu               sync.Mutex
	connections      map[net.Conn]bool
	workers          sync.WaitGroup
	closeOnce        sync.Once
	entered          chan struct{}
	enterOnce        sync.Once
	mode, label      string
	queries, accepts atomic.Int32
}

func newOCGateway(t *testing.T, mode, label string) *ocGateway {
	t.Helper()
	ca := ocIdentityNew(t, nil, 1, "", false)
	client := ocIdentityNew(t, &ca, 2, "", true)
	gateway := ocIdentityNew(t, &ca, 3, "fixture.invalid", false)
	origin := ocIdentityNew(t, &ca, 4, "owned-route.test", false)
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(ca.certPEM)) {
		t.Fatal("Owned root pool")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("Owned loopback listener")
	}
	network, err := newPacketNetwork([]netip.Addr{netip.MustParseAddr("10.2.0.1")}, nil, 1280)
	if err != nil {
		listener.Close()
		t.Fatal("Owned userspace server stack")
	}
	f := &ocGateway{profile: openConnectProfile{endpoint: "https://fixture.invalid:" + fmt.Sprint(listener.Addr().(*net.TCPAddr).Port) + "/", ca: ca.certPEM, certificate: client.certPEM, key: client.keyPEM, bootstrap: "127.0.0.1"}, roots: roots, listener: listener, network: network, connections: make(map[net.Conn]bool), entered: make(chan struct{}), mode: mode, label: label}
	udp, err := network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.2.0.1"), Port: 53})
	if err != nil {
		f.Close()
		t.Fatal("Owned DNS listener")
	}
	f.udp = udp
	originTCP, err := network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.2.0.1"), Port: 443})
	if err != nil {
		f.Close()
		t.Fatal("Owned HTTPS stack listener")
	}
	f.origin = &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, label) }), ErrorLog: log.New(io.Discard, "", 0)}
	f.workers.Add(3)
	go func() {
		defer f.workers.Done()
		f.origin.Serve(tls.NewListener(originTCP, &tls.Config{Certificates: []tls.Certificate{origin.certificate}, MinVersion: tls.VersionTLS12}))
	}()
	go func() {
		defer f.workers.Done()
		buffer := make([]byte, 512)
		for {
			n, address, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			var query dnsmessage.Message
			if query.Unpack(buffer[:n]) != nil || len(query.Questions) != 1 {
				continue
			}
			f.queries.Add(1)
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true}, Questions: query.Questions}
			q := query.Questions[0]
			if q.Name.String() == "owned-route.test." && q.Type == dnsmessage.TypeA {
				response.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{10, 2, 0, 1}}}}
			}
			answer, err := response.Pack()
			if err == nil {
				udp.WriteTo(answer, address)
			}
		}
	}()
	go func() {
		defer f.workers.Done()
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			f.accepts.Add(1)
			f.mu.Lock()
			f.connections[connection] = true
			f.workers.Add(1)
			f.mu.Unlock()
			go func() {
				defer f.workers.Done()
				defer connection.Close()
				defer func() { f.mu.Lock(); delete(f.connections, connection); f.mu.Unlock() }()
				if mode == "stall-tls" {
					var byte [1]byte
					connection.SetReadDeadline(time.Now().Add(5 * time.Second))
					if _, err := io.ReadFull(connection, byte[:]); err == nil {
						f.enterOnce.Do(func() { close(f.entered) })
						io.Copy(io.Discard, connection)
					}
					return
				}
				secure := tls.Server(connection, &tls.Config{Certificates: []tls.Certificate{gateway.certificate}, ClientCAs: roots, ClientAuth: tls.RequireAndVerifyClientCert, MinVersion: tls.VersionTLS12})
				secure.SetDeadline(time.Now().Add(8 * time.Second))
				reader := bufio.NewReaderSize(secure, headerLimit)
				for {
					request, err := http.ReadRequest(reader)
					if err != nil {
						return
					}
					if request.ContentLength < 0 || request.ContentLength > headerLimit {
						request.Body.Close()
						return
					}
					_, err = io.Copy(io.Discard, io.LimitReader(request.Body, headerLimit+1))
					request.Body.Close()
					if err != nil {
						return
					}
					if request.Header.Get("Authorization") != "" {
						return
					}
					if request.Method == "POST" {
						f.enterOnce.Do(func() { close(f.entered) })
						if mode == "truncated-length" || mode == "truncated-chunk" {
							header := "Content-Length: 1024\r\n\r\n<config-auth"
							if mode == "truncated-chunk" {
								header = "Transfer-Encoding: chunked\r\n\r\n400\r\n<config-auth"
							}
							io.WriteString(secure, "HTTP/1.1 200 OK\r\n"+header)
							secure.Close() // Genuine TLS close_notify before declared body end.
							return
						}
						if mode == "stall-auth" {
							io.Copy(io.Discard, secure)
							return
						}
						body := "<config-auth client=\"vpn\" type=\"complete\"><session-token>owned-runtime-cookie</session-token><auth id=\"success\"/></config-auth>"
						if mode == "form" {
							body = "<config-auth><auth id=\"main\"><form><input type=\"password\" name=\"password\"/></form></auth></config-auth>"
						}
						if reply, closeTLS := ocHTTPReply(mode, body); reply != "" {
							io.WriteString(secure, reply)
							if closeTLS {
								secure.Close()
								return
							}
							continue
						}
						fmt.Fprintf(secure, "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: %d\r\n\r\n%s", len(body), body)
						continue
					}
					if request.Method != "CONNECT" || request.RequestURI != "/CSCOSSLC/tunnel" || request.Header.Get("Cookie") != "webvpn=owned-runtime-cookie" {
						return
					}
					if strings.HasPrefix(mode, "bad-cstp-") {
						reply := "HTTP/1.1 200 CONNECTED\r\n"
						switch mode {
						case "bad-cstp-line":
							reply += "X-Unused: " + strings.Repeat("x", 1100) + "\r\n\r\n"
						case "bad-cstp-count":
							reply += strings.Repeat("X-Unused: x\r\n", 70) + "\r\n"
						case "bad-cstp-eof":
							reply += "X-CSTP-Version: 1\r\nX-CSTP-MTU: 1280\r\nX-CSTP-Address: 10.2.0.2\r\nX-CSTP-DNS: 10.2.0.1\r\n"
						}
						io.WriteString(secure, reply)
						secure.Close()
						return
					}
					io.WriteString(secure, "HTTP/1.1 200 CONNECTED\r\nX-CSTP-Version: 1\r\nX-CSTP-MTU: 1280\r\nX-CSTP-Address: 10.2.0.2\r\nX-CSTP-Netmask: 255.255.255.0\r\nX-CSTP-DNS: 10.2.0.1\r\n\r\n")
					f.exchange(secure, reader)
					return
				}
			}()
		}
	}()
	t.Cleanup(f.Close)
	return f
}

func ocHTTPReply(mode, body string) (string, bool) {
	prefix := "HTTP/1.1 200 OK\r\n"
	chunk := fmt.Sprintf("%x\r\n%s\r\n", len(body), body)
	switch mode {
	case "chunked":
		return prefix + "Transfer-Encoding: chunked\r\n\r\n" + chunk + "0\r\n\r\n", false
	case "http10":
		return "HTTP/1.0 200 OK\r\n\r\n" + body, true
	case "bad-length-large":
		return prefix + "Content-Length: 2147483647\r\n\r\n", true
	case "bad-length-overflow":
		return prefix + "Content-Length: 4294967297\r\n\r\n", true
	case "bad-length-syntax":
		return prefix + "Content-Length: +1\r\n\r\n", true
	case "bad-framing":
		return prefix + "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n", true
	case "bad-chunk-large":
		return prefix + "Transfer-Encoding: chunked\r\n\r\n10001\r\n", true
	case "bad-chunk-cumulative":
		return prefix + "Transfer-Encoding: chunked\r\n\r\n8000\r\n" + strings.Repeat(" ", 32768) + "\r\n8001\r\n", true
	case "bad-chunk-no-zero":
		return prefix + "Transfer-Encoding: chunked\r\n\r\n" + chunk, true
	case "bad-chunk-no-delimiter":
		return prefix + "Transfer-Encoding: chunked\r\n\r\n" + chunk + "0\r\n", true
	case "bad-http10-large":
		return "HTTP/1.0 200 OK\r\n\r\n" + strings.Repeat(" ", 65537), true
	case "bad-header-line":
		return prefix + "X-Unused: " + strings.Repeat("x", 1100) + "\r\n\r\n", true
	case "bad-header-total":
		return prefix + strings.Repeat("X-Unused: "+strings.Repeat("x", 500)+"\r\n", 20) + "\r\n", true
	case "bad-header-count":
		return prefix + strings.Repeat("X-Unused: x\r\n", 70) + "\r\n", true
	case "bad-header-fold":
		return prefix + "X-Unused: x\r\n" + strings.Repeat("\tx\r\n", 17) + "\r\n", true
	case "bad-interim-count":
		return strings.Repeat("HTTP/1.1 100 Continue\r\n\r\n", 70), true
	}
	return "", false
}

func cstpHeader(size int, kind byte) [8]byte {
	header := [8]byte{'S', 'T', 'F', 1, 0, 0, kind, 0}
	binary.BigEndian.PutUint16(header[4:6], uint16(size))
	return header
}
func (f *ocGateway) exchange(connection net.Conn, reader io.Reader) {
	var write sync.Mutex
	done := make(chan struct{})
	go func() {
		defer close(done)
		buffer := make([]byte, 1280)
		for {
			n, err := f.network.readPacket(buffer)
			if err != nil {
				return
			}
			header := cstpHeader(n, 0)
			write.Lock()
			_, err = connection.Write(append(header[:], buffer[:n]...))
			write.Unlock()
			if err != nil {
				connection.Close()
				return
			}
		}
	}()
	defer func() { connection.Close(); f.network.Close(); <-done }()
	for {
		var header [8]byte
		if _, err := io.ReadFull(reader, header[:]); err != nil {
			return
		}
		if string(header[:4]) != "STF\x01" || header[7] != 0 {
			return
		}
		size := int(binary.BigEndian.Uint16(header[4:6]))
		if size > 1280 {
			return
		}
		payload := make([]byte, size)
		if _, err := io.ReadFull(reader, payload); err != nil {
			return
		}
		switch header[6] {
		case 0:
			if !completeIPPacket(payload) {
				return
			}
			f.network.writePacket(payload)
		case 3:
			response := cstpHeader(0, 4)
			write.Lock()
			_, err := connection.Write(response[:])
			write.Unlock()
			if err != nil {
				return
			}
		case 5, 9:
			return
		case 2, 4:
		default:
			return
		}
	}
}
func (f *ocGateway) disconnect() {
	f.mu.Lock()
	defer f.mu.Unlock()
	for connection := range f.connections {
		connection.Close()
	}
}
func (f *ocGateway) Close() {
	f.closeOnce.Do(func() {
		f.listener.Close()
		f.disconnect()
		if f.origin != nil {
			f.origin.Close()
		}
		if f.udp != nil {
			f.udp.Close()
		}
		f.network.Close()
		f.workers.Wait()
	})
}
func ocFDInventory(t *testing.T) map[string]bool {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal("Owned descriptor inventory")
	}
	inventory := make(map[string]bool)
	for _, entry := range entries {
		path := "/proc/self/fd/" + entry.Name()
		target, err := os.Readlink(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		} // Closed scan FD or concurrent close.
		if err != nil {
			t.Fatal("Owned descriptor target")
		}
		info, err := os.Stat(path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			t.Fatal("Owned descriptor inode")
		}
		inventory[fmt.Sprintf("%s:%d:%s", entry.Name(), info.Sys().(*syscall.Stat_t).Ino, target)] = true
	}
	return inventory
}

func ocRetainedFD(before, after map[string]bool) []string {
	var retained []string
	for descriptor := range after {
		if !before[descriptor] {
			retained = append(retained, descriptor)
		}
	}
	return retained
}

func ocAssertNoRetainedFD(t *testing.T, before map[string]bool) {
	t.Helper()
	for _, descriptor := range ocRetainedFD(before, ocFDInventory(t)) {
		t.Errorf("Owned lifecycle retained a new descriptor: %s", descriptor)
	}
}
func ocClient(t *testing.T, route *Route, roots *x509.CertPool, wrongName bool) *http.Client {
	t.Helper()
	proxy, err := url.Parse(fmt.Sprintf("http://%s:%s@127.0.0.1:%d", localUser, localPassword, route.Port()))
	if err != nil {
		t.Fatal("Owned proxy URL")
	}
	config := &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	if wrongName {
		config.ServerName = "wrong.owned-route.test"
	}
	transport := &http.Transport{Proxy: http.ProxyURL(proxy), TLSClientConfig: config, DisableKeepAlives: true}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 5 * time.Second}
}

func TestOpenConnectOwnedRoutedHTTPSAndIsolation(t *testing.T) {
	before := ocFDInventory(t)
	gateways := []*ocGateway{newOCGateway(t, "packets", "first"), newOCGateway(t, "packets", "second")}
	tunnels := make([]*openConnectPacketTunnel, 2)
	routes := make([]*Route, 2)
	for i, gateway := range gateways {
		var err error
		tunnels[i], err = newOpenConnectPrepared(NewRoutePreparation(), gateway.profile)
		if err != nil {
			t.Fatal("Owned certificate-only native setup failed")
		}
		tunnel := tunnels[i]
		routes[i], err = newRoute(localUser, localPassword, localRealm, "owned-route.test", func(ctx context.Context, target string) (net.Conn, error) {
			return tunnel.bridge.network.DialContext(ctx, "tcp", target)
		}, func() { tunnel.Close() })
		if err != nil {
			t.Fatal("Owned authenticated route listener")
		}
		t.Cleanup(func() {
			routes[i].Close()
			if tunnel.Close() != nil {
				t.Error("Native cleanup failed")
			}
		})
	}
	for i, route := range routes {
		for _, client := range []*http.Client{ocClient(t, route, x509.NewCertPool(), false), ocClient(t, route, gateways[i].roots, true), ocClient(t, route, gateways[1-i].roots, false)} {
			response, err := client.Get("https://owned-route.test/")
			if response != nil {
				response.Body.Close()
			}
			if err == nil {
				t.Fatal("Origin certificate/hostname validation failed open")
			}
			var untrusted x509.UnknownAuthorityError
			var hostname x509.HostnameError
			if !errors.As(err, &untrusted) && !errors.As(err, &hostname) {
				t.Fatal("Origin negative failed before certificate validation")
			}
		}
	}
	var workers sync.WaitGroup
	for i, route := range routes {
		client := ocClient(t, route, gateways[i].roots, false)
		for repeat := 0; repeat < 5; repeat++ {
			workers.Add(1)
			go func(index int) {
				defer workers.Done()
				response, err := client.Get("https://owned-route.test/")
				if err != nil {
					t.Error("Encrypted routed request failed")
					return
				}
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				if err != nil || string(body) != gateways[index].label {
					t.Error("Independent route origin body mismatch")
				}
			}(i)
		}
	}
	workers.Wait()
	for i, gateway := range gateways {
		if gateway.queries.Load() == 0 {
			t.Fatal("Route did not use explicit tunneled DNS")
		}
		routes[i].Close()
		if tunnels[i].Close() != nil {
			t.Fatal("Native join/destruction failed")
		}
		waitPacketClosed(t, tunnels[i].nativeDone)
		gateway.Close()
	}
	ocAssertNoRetainedFD(t, before)
}

func TestOpenConnectOwnedRejectionAndPreparationCancellation(t *testing.T) {
	for _, mode := range []string{"wrong-ca", "wrong-host", "form", "stall-auth", "stall-tls", "before-start", "truncated-length", "truncated-chunk", "bad-length-large", "bad-length-overflow", "bad-length-syntax", "bad-framing", "bad-chunk-large", "bad-chunk-cumulative", "bad-chunk-no-zero", "bad-chunk-no-delimiter", "bad-http10-large", "bad-header-line", "bad-header-total", "bad-header-count", "bad-header-fold", "bad-interim-count", "bad-cstp-line", "bad-cstp-count", "bad-cstp-eof"} {
		t.Run(mode, func(t *testing.T) {
			before := ocFDInventory(t)
			gateway := newOCGateway(t, mode, "unused")
			profile := gateway.profile
			if mode == "wrong-ca" {
				ca := ocIdentityNew(t, nil, 99, "", false)
				profile.ca = ca.certPEM
			}
			if mode == "wrong-host" {
				profile.endpoint = strings.Replace(profile.endpoint, "fixture.invalid", "wrong.invalid", 1)
			}
			p := NewRoutePreparation()
			if mode == "before-start" {
				p.Cancel()
			}
			done := make(chan error, 1)
			go func() {
				tunnel, err := newOpenConnectPrepared(p, profile)
				if tunnel != nil {
					tunnel.Close()
					err = nil
				}
				done <- err
			}()
			if mode == "stall-auth" || mode == "stall-tls" {
				select {
				case <-gateway.entered:
				case <-time.After(3 * time.Second):
					t.Fatal("Owned preparation did not enter controlled stall")
				}
				started := time.Now()
				p.Cancel()
				if time.Since(started) > 100*time.Millisecond {
					t.Fatal("Caller-visible cancellation blocked on native work")
				}
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("Unsupported/cancelled native preparation succeeded")
				}
			case <-time.After(6 * time.Second):
				t.Fatal("Native preparation cancellation did not join")
			}
			if mode == "before-start" && gateway.accepts.Load() != 0 {
				t.Fatal("Pre-start cancellation connected a transport")
			}
			if _, err := newOpenConnectPrepared(p, profile); err == nil {
				t.Fatal("Preparation token was reused")
			}
			gateway.Close()
			if strings.HasPrefix(mode, "bad-") && !strings.HasPrefix(mode, "bad-cstp-") && gateway.accepts.Load() != 1 {
				t.Error("Rejected HTTP framing initiated another connection")
			}
			ocAssertNoRetainedFD(t, before)
		})
	}
}

func TestOpenConnectOwnedValidHTTPFraming(t *testing.T) {
	for _, mode := range []string{"chunked", "http10"} {
		t.Run(mode, func(t *testing.T) {
			before := ocFDInventory(t)
			gateway := newOCGateway(t, mode, "framing")
			tunnel, err := newOpenConnectPrepared(NewRoutePreparation(), gateway.profile)
			if err != nil {
				t.Fatal("Valid bounded HTTP framing rejected")
			}
			if tunnel.Close() != nil {
				t.Fatal("Valid framing native cleanup")
			}
			waitPacketClosed(t, tunnel.nativeDone)
			gateway.Close()
			ocAssertNoRetainedFD(t, before)
		})
	}
}

func TestOpenConnectDescriptorInventoryDetectsMaskedRetention(t *testing.T) {
	initial := ocFDInventory(t)
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal("Owned baseline pipe")
	}
	before := ocFDInventory(t)
	read.Close()
	write.Close()
	read, write, err = os.Pipe()
	if err != nil {
		t.Fatal("Owned deliberately retained pipe")
	}
	if len(ocRetainedFD(before, ocFDInventory(t))) != 2 {
		t.Error("Closing pre-existing FDs masked new retention with reused FD numbers")
	}
	read.Close()
	write.Close()
	ocAssertNoRetainedFD(t, initial)
}

func TestOpenConnectNativeFailureClosesPacketStack(t *testing.T) {
	before := ocFDInventory(t)
	gateway := newOCGateway(t, "packets", "failure")
	tunnel, err := newOpenConnectPrepared(NewRoutePreparation(), gateway.profile)
	if err != nil {
		t.Fatal("Owned native setup")
	}
	gateway.disconnect()
	waitPacketClosed(t, tunnel.nativeDone)
	if _, err := tunnel.bridge.network.DialContext(context.Background(), "tcp", "owned-route.test:443"); err == nil {
		t.Fatal("Native failure allowed route fallback")
	}
	if tunnel.Close() != nil {
		t.Fatal("Failed native session cleanup was not joined")
	}
	gateway.Close()
	ocAssertNoRetainedFD(t, before)
}
