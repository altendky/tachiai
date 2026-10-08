package routebridge

import (
	"bufio"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const localUser = "synthetic_local_user"
const localPassword = "synthetic_local_password"
const localRealm = "synthetic_local_realm"

func connect(t *testing.T, r *Route, target, auth string) (net.Conn, *bufio.Reader, int) {
	t.Helper()
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()), time.Second)
	if err != nil {
		t.Fatal("Local dial failed")
	}
	c.SetDeadline(time.Now().Add(10 * time.Second))
	io.WriteString(c, "CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n"+auth+"\r\n")
	reader := bufio.NewReader(c)
	response, err := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if err != nil {
		c.Close()
		t.Fatal("Local response failed")
	}
	return c, reader, response.StatusCode
}
func localAuth() string {
	return "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(localUser+":"+localPassword)) + "\r\n"
}

func TestBridgeAdmission(t *testing.T) {
	var calls atomic.Int32
	r, err := newRoute(localUser, localPassword, localRealm, "example.test,*-abematv.akamaized.net,*.ttvnw.net,*.twitchcdn.net", func(context.Context, string) (net.Conn, error) { calls.Add(1); return nil, errConnect }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	for _, test := range []struct {
		target, auth string
		status       int
	}{
		{"example.test:443", "", 407}, {"example.test:443", "Proxy-Authorization: Basic invalid\r\n", 407},
		{"example.test:443", localAuth() + localAuth(), 407}, {"example.test:80", localAuth(), 403},
		{"127.0.0.1:443", localAuth(), 403}, {"example.test.evil.test:443", localAuth(), 403},
		{"evil-abematv.akamaized.net:443", localAuth(), 502}, {"nested.evil-abematv.akamaized.net:443", localAuth(), 403},
		{"video-weaver.foo.hls.ttvnw.net:443", localAuth(), 502}, {"evilttvnw.net:443", localAuth(), 403},
	} {
		c, _, status := connect(t, r, test.target, test.auth)
		c.Close()
		if status != test.status {
			t.Fatalf("Wrong admission status: got %d wanted %d", status, test.status)
		}
	}
	if calls.Load() != 2 {
		t.Fatal("Rejected requests contacted transport")
	}
}

func TestMalformedHeadersAreBounded(t *testing.T) {
	for _, data := range []string{"CONNECT example.test:443 HTTP/1.1\n\n", strings.Repeat("x", headerLimit+1), "CONNECT example.test:443 HTTP/1.1\r\nX: " + strings.Repeat("x", headerLimit) + "\r\n\r\n"} {
		if _, err := readHeaders(bufio.NewReaderSize(strings.NewReader(data), headerLimit+1)); err == nil {
			t.Fatal("Malformed headers accepted")
		}
	}
}

func TestHttpProxyAuthAndBytePreservation(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	checked := make(chan bool, 1)
	go func() {
		c, e := listener.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		reader := bufio.NewReader(c)
		request, e := readHeaders(reader)
		if e != nil {
			checked <- false
			return
		}
		expected := "Basic " + base64.StdEncoding.EncodeToString([]byte("fixture:password"))
		checked <- request.Host == "example.test:443" && request.Header.Get("Proxy-Authorization") == expected
		io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\nopaque-prefix")
		io.Copy(c, reader)
	}()
	r, err := NewHttpProxy("http://fixture:password@"+listener.Addr().String(), localUser, localPassword, localRealm, "example.test")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	c, reader, status := connect(t, r, "example.test:443", localAuth())
	defer c.Close()
	if status != 200 {
		t.Fatal("Proxy did not connect")
	}
	if !<-checked {
		t.Fatal("Remote proxy auth/destination mismatch")
	}
	first := make([]byte, len("opaque-prefix"))
	if _, err := io.ReadFull(reader, first); err != nil || string(first) != "opaque-prefix" {
		t.Fatal("Buffered tunnel prefix lost")
	}
	io.WriteString(c, "opaque-TLS-bytes")
	echo := make([]byte, len("opaque-TLS-bytes"))
	if _, err := io.ReadFull(reader, echo); err != nil || string(echo) != "opaque-TLS-bytes" {
		t.Fatal("Tunnel bytes changed")
	}
}

func TestProxyRejectsResponseWithoutFallback(t *testing.T) {
	for _, reply := range []string{"HTTP/1.1 302 Found\r\nLocation: https://example.test\r\n\r\n", "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", "HTTP/1.1 200 OK\n\n", strings.Repeat("x", headerLimit+2)} {
		listener, e := net.Listen("tcp4", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		go func() {
			c, e := listener.Accept()
			if e != nil {
				return
			}
			defer c.Close()
			readHeaders(bufio.NewReader(c))
			io.WriteString(c, reply)
		}()
		r, e := NewHttpProxy("http://"+listener.Addr().String(), localUser, localPassword, localRealm, "example.test")
		if e != nil {
			t.Fatal(e)
		}
		c, _, status := connect(t, r, "example.test:443", localAuth())
		c.Close()
		r.Close()
		listener.Close()
		if status != 502 {
			t.Fatalf("Unsafe upstream response accepted: %d", status)
		}
	}
}

func TestCloseCancelsPendingDial(t *testing.T) {
	started := make(chan struct{})
	exited := make(chan struct{})
	r, err := newRoute(localUser, localPassword, localRealm, "example.test", func(ctx context.Context, _ string) (net.Conn, error) {
		close(started)
		<-ctx.Done()
		close(exited)
		return nil, errClosed
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	io.WriteString(c, "CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n"+localAuth()+"\r\n")
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("Dial did not start")
	}
	r.Close()
	r.Close()
	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("Dial did not cancel")
	}
	if _, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()), time.Second); err == nil {
		t.Fatal("Closed route still listening")
	}
}

func syntheticKeys(t *testing.T) (*ecdh.PrivateKey, *ecdh.PrivateKey) {
	t.Helper()
	a, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	b, e := ecdh.X25519().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	return a, b
}
func fixtureConfig(client, server *ecdh.PrivateKey, endpoint string) string {
	return "[Interface]\nPrivateKey = " + base64.StdEncoding.EncodeToString(client.Bytes()) + "\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n[Peer]\nPublicKey = " + base64.StdEncoding.EncodeToString(server.PublicKey().Bytes()) + "\nAllowedIPs = 10.2.0.1/32\nEndpoint = " + endpoint + "\nPersistentKeepalive = 1"
}

func TestWireGuardConfigFailsClosed(t *testing.T) {
	client, server := syntheticKeys(t)
	good := fixtureConfig(client, server, "127.0.0.1:51820")
	if _, err := parseWireGuard(good); err != nil {
		t.Fatal("Valid fixture rejected")
	}
	for _, bad := range []string{strings.Replace(good, "DNS = 10.2.0.1", "", 1), strings.Replace(good, "10.2.0.1/32", "10.4.0.1/32", 1), good + "\nPostUp = script", good + "\n[Peer]", good + "\nAllowedIPs = 0.0.0.0/0", strings.Replace(good, "Address = 10.2.0.2/32", "Address = ::ffff:10.2.0.2/128", 1), strings.Replace(good, "[Peer]", "MTU = 1\n[Peer]", 1)} {
		if _, err := parseWireGuard(bad); err == nil {
			t.Fatal("Invalid fixture accepted")
		}
	}
}

func TestActualWireGuardTunnelAndDns(t *testing.T) {
	client, server := syntheticKeys(t)
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.2.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal("Fixture netstack creation failed")
	}
	engine := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer engine.Close()
	ipc := "private_key=" + hex.EncodeToString(server.Bytes()) + "\nlisten_port=0\npublic_key=" + hex.EncodeToString(client.PublicKey().Bytes()) + "\nallowed_ip=10.2.0.2/32\n"
	if engine.IpcSet(ipc) != nil || engine.Up() != nil {
		t.Fatal("Fixture engine creation failed")
	}
	state, err := engine.IpcGet()
	if err != nil {
		t.Fatal("Fixture engine state failed")
	}
	port := ""
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		t.Fatal("Fixture endpoint unavailable")
	}
	udp, err := network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.2.0.1"), Port: 53})
	if err != nil {
		t.Fatal("Fixture DNS failed")
	}
	defer udp.Close()
	go func() {
		data := make([]byte, 2048)
		for {
			n, addr, e := udp.ReadFrom(data)
			if e != nil {
				return
			}
			var parser dnsmessage.Parser
			header, e := parser.Start(data[:n])
			if e != nil {
				continue
			}
			question, e := parser.Question()
			if e != nil {
				continue
			}
			builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: header.ID, Response: true, RecursionAvailable: true})
			builder.StartQuestions()
			builder.Question(question)
			builder.StartAnswers()
			if question.Type == dnsmessage.TypeA {
				builder.AResource(dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 30}, dnsmessage.AResource{A: [4]byte{10, 2, 0, 1}})
			}
			reply, e := builder.Finish()
			if e == nil {
				udp.WriteTo(reply, addr)
			}
		}
	}()
	tcp, err := network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.2.0.1"), Port: 443})
	if err != nil {
		t.Fatal("Fixture origin failed")
	}
	defer tcp.Close()
	go func() {
		c, e := tcp.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		io.Copy(c, c)
	}()
	r, err := NewWireGuard(fixtureConfig(client, server, "127.0.0.1:"+port), localUser, localPassword, localRealm, "example.test")
	if err != nil {
		t.Fatal("WireGuard route creation failed")
	}
	defer r.Close()
	c, reader, status := connect(t, r, "example.test:443", localAuth())
	defer c.Close()
	if status != 200 {
		t.Fatal("WireGuard CONNECT failed")
	}
	io.WriteString(c, "encrypted-opaque-fixture")
	body := make([]byte, len("encrypted-opaque-fixture"))
	if _, err := io.ReadFull(reader, body); err != nil || string(body) != "encrypted-opaque-fixture" {
		t.Fatal("WireGuard tunnel bytes did not arrive")
	}
}
