package routebridge

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

func TestSystemAdmissionBeforeDial(t *testing.T) {
	var calls atomic.Int32
	targets := make(chan string, 3)
	route, err := newSystem(localUser, localPassword, localRealm, "abema.tv,auth.abema.tv,*.abema-tv.com", func(_ context.Context, target string) (net.Conn, error) {
		calls.Add(1)
		targets <- target
		return nil, errors.New("synthetic-private-dial-detail")
	})
	if err != nil {
		t.Fatal("System fixture creation failed")
	}
	defer route.Close()
	for _, test := range []struct {
		target, auth string
		status       int
	}{
		{"abema.tv:443", "", 407},
		{"abema.tv:443", "Proxy-Authorization: Basic invalid\r\n", 407},
		{"abema.tv:443", localAuth() + localAuth(), 407},
		{"outside.test:443", localAuth(), 403},
		{"127.0.0.1:443", localAuth(), 403},
		{"[::1]:443", localAuth(), 403},
		{"abema-tv.com:443", localAuth(), 403},
		{"evilabema-tv.com:443", localAuth(), 403},
		{"gateway.abema-tv.com.outside.test:443", localAuth(), 403},
		{"gateway.abema-tv.com:80", localAuth(), 403},
		{"gateway.abema-tv.com:0443", localAuth(), 403},
	} {
		client, _, status := connect(t, route, test.target, test.auth)
		client.Close()
		if status != test.status || calls.Load() != 0 {
			t.Fatal("System refusal status or zero-dial guard failed")
		}
	}
	for _, target := range []string{"abema.tv:443", "gateway.p-c2-x.abema-tv.com:443"} {
		client, _, status := connect(t, route, target, localAuth())
		client.Close()
		if status != 502 || <-targets != target {
			t.Fatal("Approved System destination did not reach injected dial")
		}
	}
	if calls.Load() != 2 {
		t.Fatal("Unexpected System dial count")
	}
	if _, err := route.dial(context.Background(), "abema.tv:443"); err != errConnect {
		t.Fatal("Private System dial failure escaped closed error")
	}
}

func TestSystemConfigurationAndPublicFactoryClose(t *testing.T) {
	for _, config := range []struct{ user, password, realm, hosts string }{
		{"short", localPassword, localRealm, "abema.tv"},
		{localUser, "short", localRealm, "abema.tv"},
		{localUser, localPassword, "short", "abema.tv"},
		{localUser, localPassword, localRealm, ""},
		{localUser, localPassword, localRealm, "*.outside.test"},
	} {
		route, err := NewSystem(config.user, config.password, config.realm, config.hosts)
		if route != nil {
			route.Close()
			t.Fatal("Invalid System configuration allocated a route")
		}
		if err != errConfig {
			t.Fatal("Invalid System configuration lost closed error")
		}
	}
	route, err := NewSystem(localUser, localPassword, localRealm, "abema.tv,*.abema-tv.com")
	if err != nil {
		t.Fatal("Public System factory failed")
	}
	defer route.Close()
	client, _, status := connect(t, route, "outside.test:443", localAuth())
	client.Close()
	if status != 403 {
		t.Fatal("Public System factory admitted an unapproved destination")
	}
	closeSystemConcurrently(t, route)
}

func TestSystemPreservesSamePacketOpaqueBytes(t *testing.T) {
	upstream, peer := net.Pipe()
	defer peer.Close()
	defer upstream.Close()
	route, err := newSystem(localUser, localPassword, localRealm, "*.abema-tv.com", func(context.Context, string) (net.Conn, error) {
		return upstream, nil
	})
	if err != nil {
		t.Fatal("System fixture creation failed")
	}
	defer route.Close()
	payload := []byte{0x16, 0x03, 0x03, 0x00, 0xff, 'o', 'p', 'a', 'q', 'u', 'e'}
	reply := []byte{0x17, 0x03, 0x03, 0x00, 0x80, 'r', 'e', 'p', 'l', 'y'}
	checked := make(chan bool, 1)
	go func() {
		peer.SetDeadline(time.Now().Add(5 * time.Second))
		received := make([]byte, len(payload))
		_, readErr := io.ReadFull(peer, received)
		_, writeErr := peer.Write(reply)
		checked <- readErr == nil && writeErr == nil && bytes.Equal(received, payload)
	}()
	client, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", route.Port()), time.Second)
	if err != nil {
		t.Fatal("Local System dial failed")
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(5 * time.Second))
	header := "CONNECT gateway.p-c2-x.abema-tv.com:443 HTTP/1.1\r\nHost: gateway.p-c2-x.abema-tv.com:443\r\n" + localAuth() + "\r\n"
	if _, err := client.Write(append([]byte(header), payload...)); err != nil {
		t.Fatal("Same-packet CONNECT write failed")
	}
	reader := bufio.NewReader(client)
	response, err := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if err != nil || response.StatusCode != 200 {
		t.Fatal("Opaque System tunnel was not established")
	}
	received := make([]byte, len(reply))
	if _, err := io.ReadFull(reader, received); err != nil || !bytes.Equal(received, reply) || !<-checked {
		t.Fatal("Opaque System tunnel changed bytes or forwarded CONNECT credentials")
	}
}

func TestSystemCloseCancelsPendingDial(t *testing.T) {
	started, exited := make(chan struct{}), make(chan struct{})
	route, err := newSystem(localUser, localPassword, localRealm, "abema.tv", func(ctx context.Context, _ string) (net.Conn, error) {
		close(started)
		<-ctx.Done()
		close(exited)
		return nil, ctx.Err()
	})
	if err != nil {
		t.Fatal("System fixture creation failed")
	}
	defer route.Close()
	client, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", route.Port()), time.Second)
	if err != nil {
		t.Fatal("Local System dial failed")
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(client, "CONNECT abema.tv:443 HTTP/1.1\r\nHost: abema.tv:443\r\n"+localAuth()+"\r\n"); err != nil {
		t.Fatal("Pending System CONNECT write failed")
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("System dial did not start")
	}
	closeSystemConcurrently(t, route)
	select {
	case <-exited:
	default:
		t.Fatal("System close returned before pending dial exited")
	}
}

func TestSystemCloseCancelsActiveTunnel(t *testing.T) {
	upstream, peer := net.Pipe()
	defer peer.Close()
	defer upstream.Close()
	route, err := newSystem(localUser, localPassword, localRealm, "abema.tv", func(context.Context, string) (net.Conn, error) {
		return upstream, nil
	})
	if err != nil {
		t.Fatal("System fixture creation failed")
	}
	defer route.Close()
	client, reader, status := connect(t, route, "abema.tv:443", localAuth())
	defer client.Close()
	if status != 200 {
		t.Fatal("Active System tunnel was not established")
	}
	closeSystemConcurrently(t, route)
	peer.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := peer.Read(make([]byte, 1)); err != io.EOF {
		t.Fatal("System close left the upstream tunnel open")
	}
	if _, err := reader.ReadByte(); err == nil {
		t.Fatal("System close left the client tunnel open")
	}
}

func closeSystemConcurrently(t *testing.T, route *Route) {
	t.Helper()
	results := make(chan error, 8)
	for range 8 {
		go func() { results <- route.Close() }()
	}
	for range 8 {
		select {
		case err := <-results:
			if err != nil {
				t.Fatal("Concurrent System close failed")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("Concurrent System close exceeded fixture bound")
		}
	}
	if route.Close() != nil {
		t.Fatal("Repeated System close failed")
	}
	client, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", route.Port()), time.Second)
	if err == nil {
		client.Close()
		t.Fatal("Closed System route still listening")
	}
}
