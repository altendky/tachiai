package routebridge

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type wgConfig struct {
	addresses, dns []netip.Addr
	mtu            int
	ipc            string
	endpoint       string
}

func keyHex(value string) (string, error) {
	key, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(key) != 32 || base64.StdEncoding.EncodeToString(key) != value {
		return "", errConfig
	}
	zero := true
	for _, b := range key {
		if b != 0 {
			zero = false
		}
	}
	if zero {
		return "", errConfig
	}
	return hex.EncodeToString(key), nil
}

func parseWireGuard(config string) (wgConfig, error) {
	result := wgConfig{mtu: 1280}
	if len(config) == 0 || len(config) > headerLimit {
		return result, errConfig
	}
	fields := make(map[string]string)
	section := ""
	interfaces, peers := 0, 0
	for _, raw := range strings.Split(config, "\n") {
		line := strings.TrimSpace(strings.SplitN(raw, "#", 2)[0])
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			switch line {
			case "[Interface]":
				interfaces++
				if interfaces != 1 || peers != 0 {
					return result, errConfig
				}
				section = "Interface"
			case "[Peer]":
				peers++
				if interfaces != 1 || peers != 1 {
					return result, errConfig
				}
				section = "Peer"
			default:
				return result, errConfig
			}
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 || section == "" {
			return result, errConfig
		}
		name, value := strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1])
		field := section + "." + name
		if value == "" || strings.ContainsAny(value, "\r\n\x00") {
			return result, errConfig
		}
		if _, exists := fields[field]; exists {
			return result, errConfig
		}
		switch field {
		case "Interface.PrivateKey", "Interface.Address", "Interface.DNS", "Interface.MTU", "Peer.PublicKey", "Peer.PresharedKey", "Peer.AllowedIPs", "Peer.Endpoint", "Peer.PersistentKeepalive":
		default:
			return result, errConfig
		}
		fields[field] = value
	}
	if interfaces != 1 || peers != 1 {
		return result, errConfig
	}
	private, err := keyHex(fields["Interface.PrivateKey"])
	if err != nil {
		return result, errConfig
	}
	public, err := keyHex(fields["Peer.PublicKey"])
	if err != nil {
		return result, errConfig
	}
	for _, address := range strings.Split(fields["Interface.Address"], ",") {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(address))
		if err != nil || prefix.Addr().Is4In6() || prefix.Addr().IsUnspecified() {
			return result, errConfig
		}
		result.addresses = append(result.addresses, prefix.Addr())
	}
	if len(result.addresses) > 16 {
		return result, errConfig
	}
	for _, dns := range strings.Split(fields["Interface.DNS"], ",") {
		ip, err := netip.ParseAddr(strings.TrimSpace(dns))
		if err != nil || ip.Is4In6() || ip.IsUnspecified() || ip.Zone() != "" {
			return result, errConfig
		}
		result.dns = append(result.dns, ip)
	}
	if len(result.dns) > 16 {
		return result, errConfig
	}
	if value, exists := fields["Interface.MTU"]; exists {
		mtu, err := strconv.Atoi(value)
		if err != nil || mtu < 576 || mtu > 9000 {
			return result, errConfig
		}
		result.mtu = mtu
	}
	for _, address := range result.addresses {
		if address.Is6() && result.mtu < 1280 {
			return result, errConfig
		}
	}
	host, port, err := net.SplitHostPort(fields["Peer.Endpoint"])
	if err != nil {
		return result, errConfig
	}
	portValue, err := strconv.Atoi(port)
	if err != nil || portValue < 1 || portValue > 65535 {
		return result, errConfig
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if ip.Is4In6() || ip.Zone() != "" || ip.IsUnspecified() {
			return result, errConfig
		}
	} else if !validHost(strings.ToLower(host)) {
		return result, errConfig
	}
	var ipc strings.Builder
	ipc.WriteString("private_key=" + private + "\nreplace_peers=true\npublic_key=" + public + "\n")
	if value, exists := fields["Peer.PresharedKey"]; exists {
		key, err := keyHex(value)
		if err != nil {
			return result, errConfig
		}
		ipc.WriteString("preshared_key=" + key + "\n")
	}
	ipc.WriteString("endpoint=" + net.JoinHostPort(host, port) + "\nreplace_allowed_ips=true\n")
	allowed := make([]netip.Prefix, 0)
	for _, value := range strings.Split(fields["Peer.AllowedIPs"], ",") {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(value))
		if err != nil || prefix.Addr().Is4In6() {
			return result, errConfig
		}
		allowed = append(allowed, prefix.Masked())
		ipc.WriteString("allowed_ip=" + prefix.Masked().String() + "\n")
	}
	if len(allowed) > 16 {
		return result, errConfig
	}
	// Configured DNS must actually be routable through this peer. No host DNS
	// fallback or replacement DNS server is invented for incomplete profiles.
	for _, dns := range result.dns {
		covered := false
		hasFamily := false
		for _, prefix := range allowed {
			if prefix.Contains(dns) {
				covered = true
			}
		}
		for _, address := range result.addresses {
			if address.Is4() == dns.Is4() {
				hasFamily = true
			}
		}
		if !covered || !hasFamily {
			return result, errConfig
		}
	}
	if value, exists := fields["Peer.PersistentKeepalive"]; exists {
		if value == "off" {
			value = "0"
		}
		n, err := strconv.Atoi(value)
		if err != nil || n < 0 || n > 65535 {
			return result, errConfig
		}
		ipc.WriteString("persistent_keepalive_interval=" + strconv.Itoa(n) + "\n")
	}
	result.ipc = ipc.String()
	result.endpoint = net.JoinHostPort(host, port)
	return result, nil
}

// NewWireGuard creates an in-memory IP/TCP/DNS stack: no Android VpnService,
// root, OS routes or packet interception. Outer endpoint DNS/UDP uses the
// system connection; destination DNS and TCP use only the selected peer.
func NewWireGuard(config, username, password, realm, allowedHosts string) (*Route, error) {
	parsed, err := parseWireGuard(config)
	if err != nil {
		return nil, errConfig
	}
	// Upstream UAPI accepts numeric endpoint addresses, not hostnames. Only the
	// outer tunnel endpoint is resolved on the system path, under a deadline.
	host, port, _ := net.SplitHostPort(parsed.endpoint)
	if _, err := netip.ParseAddr(host); err != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		addresses, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		cancel()
		if err != nil || len(addresses) == 0 {
			return nil, errConnect
		}
		endpoint := net.JoinHostPort(addresses[0].Unmap().String(), port)
		parsed.ipc = strings.Replace(parsed.ipc, "endpoint="+parsed.endpoint+"\n", "endpoint="+endpoint+"\n", 1)
	}
	tun, network, err := netstack.CreateNetTUN(parsed.addresses, parsed.dns, parsed.mtu)
	if err != nil {
		return nil, errConnect
	}
	logger := device.NewLogger(device.LogLevelSilent, "")
	logger.Verbosef = func(string, ...any) {}
	logger.Errorf = func(string, ...any) {}
	engine := device.NewDevice(tun, conn.NewDefaultBind(), logger)
	if engine.IpcSet(parsed.ipc) != nil || engine.Up() != nil {
		engine.Close()
		return nil, errConnect
	}
	route, err := newRoute(username, password, realm, allowedHosts, func(ctx context.Context, target string) (net.Conn, error) {
		return network.DialContext(ctx, "tcp", target)
	}, engine.Close)
	if err != nil {
		engine.Close()
		return nil, err
	}
	return route, nil
}
