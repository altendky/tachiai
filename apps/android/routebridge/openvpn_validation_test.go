//go:build openvpn_validation && cgo

package routebridge

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Invoked only by the isolated server-side TUN fixture. The two clients still
// use native-owned AF_UNIX packet descriptors and private userspace stacks.
func TestOpenVPNNativeRoutedData(t *testing.T) {
	root := os.Getenv("TACHIAI_OPENVPN_ROUTED_ROOT")
	if root == "" {
		t.Skip("Requires owned encrypted routed-data fixture")
	}
	pendingProfile, err := os.ReadFile(filepath.Join(root, "2", "client.ovpn"))
	if err != nil {
		t.Fatal("Owned pending-auth profile unavailable")
	}
	started := time.Now()
	pendingTunnel, err := newOpenVPNPacketTunnel(context.Background(), string(pendingProfile))
	if pendingTunnel != nil {
		pendingTunnel.Close()
	}
	if err != errConnect || pendingTunnel != nil || time.Since(started) > 2*time.Second {
		t.Fatal("Certificate-only route did not promptly refuse server AUTH_PENDING")
	}
	var tunnels [2]*openVPNPacketTunnel
	var routes [2]*Route
	var cancel [2]context.CancelFunc
	var clients [2]*http.Client
	for index := range 2 {
		fixture := filepath.Join(root, fmt.Sprint(index))
		content, err := os.ReadFile(filepath.Join(fixture, "client.ovpn"))
		if err != nil {
			t.Fatal("Owned profile unavailable")
		}
		var ctx context.Context
		ctx, cancel[index] = context.WithTimeout(context.Background(), 25*time.Second)
		t.Cleanup(cancel[index])
		tunnels[index], err = newOpenVPNPacketTunnel(ctx, string(content))
		if err != nil {
			t.Fatal("Owned encrypted tunnel preparation failed")
		}
		t.Cleanup(func() {
			if err := tunnels[index].Close(); err != nil {
				t.Error("Owned tunnel cleanup unconfirmed")
			}
		})
		tunnel := tunnels[index]
		routes[index], err = newRouteWithCleanup(localUser, localPassword, localRealm, "origin.owned-route.test", func(ctx context.Context, target string) (net.Conn, error) {
			return tunnel.bridge.network.DialContext(ctx, "tcp", target)
		}, tunnel.Close)
		if err != nil {
			t.Fatal("Owned loopback route failed")
		}
		t.Cleanup(func() { routes[index].Close() })
		certificate, err := os.ReadFile(filepath.Join(fixture, "origin-ca.pem"))
		if err != nil {
			t.Fatal("Owned origin trust unavailable")
		}
		roots := x509.NewCertPool()
		if !roots.AppendCertsFromPEM(certificate) {
			t.Fatal("Owned origin trust invalid")
		}
		proxy, _ := url.Parse(fmt.Sprintf("http://%s:%s@127.0.0.1:%d", localUser, localPassword, routes[index].Port()))
		transport := &http.Transport{Proxy: http.ProxyURL(proxy), TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}, DisableKeepAlives: true}
		t.Cleanup(transport.CloseIdleConnections)
		clients[index] = &http.Client{Transport: transport, Timeout: 8 * time.Second}
		for _, failure := range []string{"untrusted-ca", "wrong-name"} {
			bad := transport.Clone()
			if failure == "untrusted-ca" {
				bad.TLSClientConfig.RootCAs = x509.NewCertPool()
			} else {
				bad.TLSClientConfig.ServerName = "wrong.owned-route.test"
			}
			response, err := (&http.Client{Transport: bad, Timeout: 3 * time.Second}).Get("https://origin.owned-route.test/")
			if response != nil {
				response.Body.Close()
			}
			bad.CloseIdleConnections()
			var unknown x509.UnknownAuthorityError
			var hostname x509.HostnameError
			if (failure == "untrusted-ca" && !errors.As(err, &unknown)) || (failure == "wrong-name" && !errors.As(err, &hostname)) {
				t.Fatal("Origin TLS failure was not certificate validation")
			}
		}
	}
	check := func(index int) {
		t.Helper()
		response, err := clients[index].Get("https://origin.owned-route.test/")
		if err != nil {
			t.Fatal("Encrypted routed DNS/TCP/HTTPS failed")
		}
		defer response.Body.Close()
		payload, err := io.ReadAll(io.LimitReader(response.Body, 100*1024))
		marker := []byte([]string{"identity-A", "identity-B"}[index])
		if err != nil || response.StatusCode != 200 || !bytes.Equal(payload, bytes.Repeat(marker, 4096)) {
			t.Fatal("Independent tunnel origin payload mismatch")
		}
	}
	// Simultaneous engines and identical inner addresses must select their own
	// server origin, even after the other tunnel has been cancelled.
	check(0)
	check(1)
	stream := func(index int) io.ReadCloser {
		t.Helper()
		response, err := clients[index].Get("https://origin.owned-route.test/stream")
		if err != nil {
			t.Fatal("Owned stream failed")
		}
		first := make([]byte, 11)
		if _, err := io.ReadFull(response.Body, first); err != nil {
			t.Fatal("Owned stream did not transfer before stop")
		}
		return response.Body
	}
	assertStopped := func(body io.ReadCloser, tunnel *openVPNPacketTunnel, limit time.Duration) {
		t.Helper()
		defer body.Close()
		ended := make(chan error, 1)
		go func() { _, err := io.Copy(io.Discard, body); ended <- err }()
		select {
		case err := <-ended:
			if err == nil {
				t.Fatal("Incomplete owned stream ended without failure")
			}
		case <-time.After(limit):
			t.Fatal("Traffic did not fail within stop bound")
		}
		waitPacketClosed(t, tunnel.done)
	}
	body := stream(0)
	cancel[0]()
	assertStopped(body, tunnels[0], time.Second)
	check(1)
	body = stream(1)
	if err := os.WriteFile(filepath.Join(root, "disconnect-second"), []byte("owned-server-stop"), 0600); err != nil {
		t.Fatal("Owned disconnect trigger failed")
	}
	assertStopped(body, tunnels[1], 6*time.Second)
}

// Invoked by native/fixtures.py with a generated profile and owned server.
func TestOpenVPNNativeOwnedFixture(t *testing.T) {
	path := os.Getenv("TACHIAI_OPENVPN_FIXTURE_PROFILE")
	if path == "" {
		t.Skip("Requires generated owned native fixture")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("Owned profile unavailable")
	}
	preparation := NewRoutePreparation()
	defer preparation.Cancel()
	result := NewOpenVPNPrepared(string(content), localUser, localPassword, localRealm, "owned-route.test", preparation)
	if os.Getenv("TACHIAI_OPENVPN_FIXTURE_MODE") != "connected" {
		if result.GetCode() != 2 || result.GetRoute() != nil {
			if result.GetRoute() != nil {
				result.GetRoute().Close()
			}
			t.Fatal("Invalid TLS peer did not produce fixed TLS refusal")
		}
		return
	}
	if result.GetCode() != 0 || result.GetRoute() == nil || result.GetRoute().Port() <= 0 {
		t.Fatal("Owned native/Go packet integration failed")
	}
	if reused := NewOpenVPNPrepared(string(content), localUser, localPassword, localRealm, "owned-route.test", preparation); reused.GetCode() != 1 || reused.GetRoute() != nil {
		t.Fatal("Preparation token was reused")
	}
	var callers sync.WaitGroup
	for range 8 {
		callers.Add(1)
		go func() {
			defer callers.Done()
			preparation.Cancel()
			if result.GetRoute().Close() != nil {
				t.Error("Owned prepared route cleanup unconfirmed")
			}
		}()
	}
	closed := make(chan struct{})
	go func() { callers.Wait(); close(closed) }()
	waitPacketClosed(t, closed)
}

func TestOpenVPNNativePreparationCancellation(t *testing.T) {
	path := os.Getenv("TACHIAI_OPENVPN_FIXTURE_PROFILE")
	if path == "" {
		t.Skip("Requires generated owned native fixture")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("Owned profile unavailable")
	}
	hostname := bytes.Replace(content, []byte("remote 127.0.0.1 "), []byte("remote hostname.owned-route.test "), 1)
	result := NewOpenVPNPrepared(string(hostname), localUser, localPassword, localRealm, "owned-route.test", NewRoutePreparation())
	if result.GetCode() != 1 || result.GetRoute() != nil {
		t.Fatal("Hostname bootstrap profile was not refused before networking")
	}
	preparation := NewRoutePreparation()
	preparation.Cancel()
	result = NewOpenVPNPrepared(string(content), localUser, localPassword, localRealm, "owned-route.test", preparation)
	if result.GetCode() != 4 || result.GetRoute() != nil {
		t.Fatal("Prepared API lost pre-start cancellation")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if tunnel, err := newOpenVPNPacketTunnel(ctx, string(content)); err != errClosed || tunnel != nil {
		t.Fatal("Cancelled preparation started")
	}
	ctx, cancel = context.WithTimeout(context.Background(), 75*time.Millisecond)
	defer cancel()
	started := time.Now()
	if tunnel, err := newOpenVPNPacketTunnel(ctx, string(content)); err != errClosed || tunnel != nil {
		if tunnel != nil {
			tunnel.Close()
		}
		t.Fatal("Pending native connection did not cancel")
	}
	if time.Since(started) > time.Second {
		t.Fatal("Native preparation cancellation exceeded bound")
	}
}
