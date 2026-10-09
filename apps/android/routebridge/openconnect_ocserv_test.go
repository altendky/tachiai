//go:build openconnect_validation && cgo

package routebridge

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func ocDocker(t *testing.T, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, "docker", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("Owned Docker operation failed: %s: %s", args[0], output)
	}
	return strings.TrimSpace(string(output))
}

type ocservFixture struct {
	profile          openConnectProfile
	roots            *x509.CertPool
	container, label string
}

func newOCServFixture(t *testing.T, image, network, label string) ocservFixture {
	t.Helper()
	ca := ocIdentityNew(t, nil, 1, "", false)
	client := ocIdentityNew(t, &ca, 2, "", true)
	gateway := ocIdentityNew(t, &ca, 3, "fixture.invalid", false)
	origin := ocIdentityNew(t, &ca, 4, "owned-route.test", false)
	root := t.TempDir()
	// Synthetic server keys only. Client credential PEM stays in memory.
	if os.Chmod(root, 0755) != nil {
		t.Fatal("Owned mount access")
	}
	config := `auth = "certificate"
cert-user-oid = 2.5.4.3
tcp-port = 4443
udp-port = 0
run-as-user = root
run-as-group = root
socket-file = /tmp/ocserv-socket
pid-file = /tmp/ocserv.pid
server-cert = /fixture/server.pem
server-key = /fixture/server.key
ca-cert = /fixture/ca.pem
isolate-workers = false
max-clients = 2
max-same-clients = 1
rate-limit-ms = 0
keepalive = 60
dpd = 60
cert-user-oid = 2.5.4.3
compression = false
tls-priorities = "NORMAL:-VERS-TLS1.0:-VERS-TLS1.1"
auth-timeout = 15
cookie-timeout = 15
persistent-cookies = false
rekey-time = 0
use-occtl = false
log-level = 2
device = owned
ipv4-network = 10.2.0.0
ipv4-netmask = 255.255.255.0
dns = 10.2.0.1
ping-leases = false
mtu = 1280
cisco-client-compat = false
dtls-psk = false
dtls-legacy = false
client-bypass-protocol = false
`
	for name, content := range map[string]string{"ca.pem": ca.certPEM, "server.pem": gateway.certPEM, "server.key": gateway.keyPEM, "origin.pem": origin.certPEM, "origin.key": origin.keyPEM, "ocserv.conf": config} {
		if os.WriteFile(filepath.Join(root, name), []byte(content), 0644) != nil {
			t.Fatal("Owned server mount")
		}
	}
	container := ocDocker(t, "run", "--detach", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN", "--cap-add", "SETGID", "--device", "/dev/net/tun", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m", "--network", network, "--publish", "127.0.0.1::4443/tcp", "--mount", "type=bind,src="+root+",dst=/fixture,readonly", "--env", "TACHIAI_OWNED_MARKER="+label, image)
	t.Cleanup(func() {
		logs := ocDocker(t, "logs", container)
		t.Logf("Owned server %s evidence: %s", label, logs)
		ocDocker(t, "rm", "--force", container)
	})
	var inspection []struct {
		HostConfig struct {
			Privileged      bool
			NetworkMode     string
			CapAdd, CapDrop []string
		}
		NetworkSettings struct {
			Ports map[string][]struct{ HostIp, HostPort string }
		}
	}
	if json.Unmarshal([]byte(ocDocker(t, "inspect", container)), &inspection) != nil || len(inspection) != 1 {
		t.Fatal("Owned isolation inventory")
	}
	host := inspection[0].HostConfig
	if host.Privileged || host.NetworkMode != network || strings.Join(host.CapAdd, ",") != "CAP_NET_ADMIN,CAP_SETGID" || strings.Join(host.CapDrop, ",") != "ALL" {
		t.Fatal("Unauthorized owned server isolation")
	}
	ports := inspection[0].NetworkSettings.Ports["4443/tcp"]
	if len(ports) != 1 || ports[0].HostIp != "127.0.0.1" {
		t.Fatal("Owned bootstrap must be loopback only")
	}
	address := "127.0.0.1:" + ports[0].HostPort
	deadline := time.Now().Add(5 * time.Second)
	for {
		conn, err := net.DialTimeout("tcp4", address, 100*time.Millisecond)
		if err == nil {
			conn.Close()
			if strings.Contains(ocDocker(t, "logs", container), "initialized ocserv 1.1.6") {
				break
			}
		}
		if time.Now().After(deadline) {
			t.Fatal("Owned ocserv did not listen")
		}
		time.Sleep(50 * time.Millisecond)
	}
	roots := x509.NewCertPool()
	if ocDocker(t, "exec", container, "ip", "route", "show", "default") != "" {
		t.Fatal("Owned server retained outbound default route")
	}
	if !roots.AppendCertsFromPEM([]byte(ca.certPEM)) {
		t.Fatal("Owned root")
	}
	return ocservFixture{openConnectProfile{"https://fixture.invalid:" + ports[0].HostPort + "/", ca.certPEM, client.certPEM, client.keyPEM, "127.0.0.1"}, roots, container, label}
}

func TestOpenConnectOwnedOCServInteroperability(t *testing.T) {
	image := os.Getenv("TACHIAI_OCSERV_IMAGE")
	if image == "" {
		t.Skip("Explicit immutable owned ocserv image required")
	}
	if !strings.HasPrefix(image, "sha256:") {
		t.Fatal("Owned image must be immutable")
	}
	before := ocFDInventory(t)
	network := ocDocker(t, "network", "create", "tachiai-ocserv-"+fmt.Sprint(os.Getpid()))
	t.Cleanup(func() { ocDocker(t, "network", "rm", network) })
	fixtures := []ocservFixture{newOCServFixture(t, image, network, "first"), newOCServFixture(t, image, network, "second")}
	tunnels := make([]*openConnectPacketTunnel, 2)
	routes := make([]*Route, 2)
	for i, fixture := range fixtures {
		var err error
		tunnels[i], err = newOpenConnectPrepared(NewRoutePreparation(), fixture.profile)
		if err != nil {
			t.Fatalf("Actual certificate-only ocserv preparation rejected: %v", err)
		}
		tunnel := tunnels[i]
		routes[i], err = newRoute(localUser, localPassword, localRealm, "owned-route.test", func(ctx context.Context, target string) (net.Conn, error) {
			return tunnel.bridge.network.DialContext(ctx, "tcp", target)
		}, func() { tunnel.Close() })
		if err != nil {
			t.Fatal("Owned authenticated route")
		}
		t.Cleanup(func() {
			routes[i].Close()
			if tunnel.Close() != nil {
				t.Error("Actual native cleanup failed")
			}
		})
	}
	var workers sync.WaitGroup
	for _, fixture := range fixtures {
		deadline := time.Now().Add(3 * time.Second)
		for {
			logs := ocDocker(t, "logs", fixture.container)
			if strings.Contains(logs, "owned-dns-ready") && strings.Contains(logs, "owned-https-ready") {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("Owned server endpoints did not bind: %s", ocDocker(t, "exec", fixture.container, "ip", "address"))
			}
			time.Sleep(50 * time.Millisecond)
		}
	}
	for i, route := range routes {
		client := ocClient(t, route, fixtures[i].roots, false)
		workers.Add(1)
		go func(index int, client *http.Client) {
			defer workers.Done()
			for n := 0; n < 5; n++ {
				response, err := client.Get("https://owned-route.test/")
				if err != nil {
					t.Errorf("Actual encrypted routed HTTPS failed: %v", err)
					return
				}
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				if err != nil || string(body) != fixtures[index].label {
					t.Error("Actual independent route body mismatch")
				}
			}
		}(i, client)
	}
	workers.Wait()
	for i, route := range routes {
		route.Close()
		if tunnels[i].Close() != nil {
			t.Fatal("Actual native cleanup not confirmed")
		}
		waitPacketClosed(t, tunnels[i].nativeDone)
		logs := ocDocker(t, "logs", fixtures[i].container)
		if !strings.Contains(logs, "owned-dns-response") || !strings.Contains(logs, "owned-https-response") {
			t.Fatal("Owned server did not observe tunneled DNS and HTTPS")
		}
	}
	ocAssertNoRetainedFD(t, before)
}
