package routebridge

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const packetDNSAddressLimit = 16

// Own the SDK stack and its concurrency-safe packet queue directly. No
// WriteNotify callback or separately closed notification channel is involved.
type packetNetwork struct {
	mutex      sync.Mutex
	closed     bool
	dials      sync.WaitGroup
	lifetime   context.Context
	cancel     context.CancelFunc
	stack      *stack.Stack
	endpoint   *channel.Endpoint
	dns        []netip.Addr
	has4, has6 bool
}

func newPacketNetwork(addresses, dns []netip.Addr, mtu int) (*packetNetwork, error) {
	n := &packetNetwork{stack: stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
		HandleLocal:        true,
	}), endpoint: channel.New(128, uint32(mtu), ""), dns: append([]netip.Addr(nil), dns...)}
	n.lifetime, n.cancel = context.WithCancel(context.Background())
	failed := true
	defer func() {
		if failed {
			n.Close()
		}
	}()
	sack := tcpip.TCPSACKEnabled(true)
	if n.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack) != nil || n.stack.CreateNIC(1, n.endpoint) != nil {
		return nil, errConnect
	}
	for _, address := range addresses {
		_, protocol := packetAddress(address, 0)
		if n.stack.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: protocol, AddressWithPrefix: tcpip.AddrFromSlice(address.AsSlice()).WithPrefix()}, stack.AddressProperties{}) != nil {
			return nil, errConnect
		}
		n.has4 = n.has4 || address.Is4()
		n.has6 = n.has6 || address.Is6()
	}
	if n.has4 {
		n.stack.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	}
	if n.has6 {
		n.stack.AddRoute(tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: 1})
	}
	failed = false
	return n, nil
}

func packetAddress(address netip.Addr, port uint16) (tcpip.FullAddress, tcpip.NetworkProtocolNumber) {
	protocol := ipv6.ProtocolNumber
	if address.Is4() {
		protocol = ipv4.ProtocolNumber
	}
	return tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(address.AsSlice()), Port: port}, protocol
}

func (n *packetNetwork) Close() {
	// SDK queue Close is explicitly safe against concurrent WritePackets.
	// Stop protocol workers and wait before releasing the owning bridge.
	n.mutex.Lock()
	n.closed = true
	n.cancel()
	n.mutex.Unlock()
	n.dials.Wait() // Cancelled preparation cannot create an endpoint during Close.
	n.endpoint.Close()
	n.stack.Close()
	n.stack.Wait()
}

func (n *packetNetwork) readPacket(buffer []byte) (int, error) {
	packet := n.endpoint.ReadContext(context.Background())
	if packet == nil {
		return 0, errClosed
	}
	view := packet.ToView()
	packet.DecRef()
	defer view.Release()
	if view.Size() > len(buffer) {
		return 0, errConnect
	}
	return copy(buffer, view.AsSlice()), nil
}

func (n *packetNetwork) writePacket(bytes []byte) {
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(bytes)})
	defer packet.DecRef()
	protocol := ipv6.ProtocolNumber
	if bytes[0]>>4 == 4 {
		protocol = ipv4.ProtocolNumber
	}
	n.endpoint.InjectInbound(protocol, packet)
}

func (n *packetNetwork) ListenUDP(address *net.UDPAddr) (*gonet.UDPConn, error) {
	n.mutex.Lock()
	defer n.mutex.Unlock()
	if n.closed {
		return nil, errClosed
	}
	ip, ok := netip.AddrFromSlice(address.IP)
	if !ok {
		return nil, errConfig
	}
	full, protocol := packetAddress(ip.Unmap(), uint16(address.Port))
	return gonet.DialUDP(n.stack, &full, nil, protocol)
}
func (n *packetNetwork) DialUDP(local, remote *net.UDPAddr) (*gonet.UDPConn, error) {
	n.mutex.Lock()
	defer n.mutex.Unlock()
	if n.closed {
		return nil, errClosed
	}
	ip, ok := netip.AddrFromSlice(remote.IP)
	if !ok {
		return nil, errConfig
	}
	full, protocol := packetAddress(ip.Unmap(), uint16(remote.Port))
	var bind *tcpip.FullAddress
	if local != nil {
		ip, ok := netip.AddrFromSlice(local.IP)
		if !ok {
			return nil, errConfig
		}
		address, _ := packetAddress(ip.Unmap(), uint16(local.Port))
		bind = &address
	}
	return gonet.DialUDP(n.stack, bind, &full, protocol)
}
func (n *packetNetwork) ListenTCP(address *net.TCPAddr) (*gonet.TCPListener, error) {
	n.mutex.Lock()
	defer n.mutex.Unlock()
	if n.closed {
		return nil, errClosed
	}
	ip, ok := netip.AddrFromSlice(address.IP)
	if !ok {
		return nil, errConfig
	}
	full, protocol := packetAddress(ip.Unmap(), uint16(address.Port))
	return gonet.ListenTCP(n.stack, full, protocol)
}

func (n *packetNetwork) dialTCP(ctx context.Context, address netip.Addr, port uint16) (net.Conn, error) {
	if address.Is4() && !n.has4 || address.Is6() && !n.has6 {
		return nil, errConnect
	}
	full, protocol := packetAddress(address, port)
	connection, err := gonet.DialContextTCP(ctx, n.stack, full, protocol)
	if err != nil {
		return nil, err
	}
	return connection, nil
}

// DNS answers are bounded before spawning candidates. Join every losing dial
// after cancellation so the enclosing lifetime operation owns all endpoint work.
func (n *packetNetwork) dialCandidates(ctx context.Context, addresses []netip.Addr, port uint16) net.Conn {
	if len(addresses) == 0 || len(addresses) > packetDNSAddressLimit {
		return nil
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan net.Conn, len(addresses))
	for _, address := range addresses {
		go func() {
			connection, err := n.dialTCP(ctx, address, port)
			if err != nil {
				connection = nil
			}
			results <- connection
		}()
	}
	var winner net.Conn
	for range addresses {
		connection := <-results
		if connection == nil {
			continue
		}
		if winner == nil {
			winner = connection
			cancel()
		} else {
			connection.Close()
		}
	}
	return winner
}

func (n *packetNetwork) DialContext(ctx context.Context, network, target string) (net.Conn, error) {
	n.mutex.Lock()
	if n.closed {
		n.mutex.Unlock()
		return nil, errClosed
	}
	n.dials.Add(1)
	n.mutex.Unlock()
	defer n.dials.Done()
	if network != "tcp" {
		return nil, errConfig
	}
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return nil, errConfig
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return nil, errConfig
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	stop := context.AfterFunc(n.lifetime, cancel)
	defer stop()
	if ip, err := netip.ParseAddr(host); err == nil {
		return n.dialTCP(ctx, ip, uint16(port))
	}
	if !validHost(host) || len(n.dns) == 0 {
		return nil, errConnect
	}
	// Resolve and dial each supported family independently. A silent AAAA
	// resolver must not consume the deadline for an already reachable A answer.
	type result struct{ connection net.Conn }
	results := make(chan result, 2)
	remaining := 0
	for _, kind := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA} {
		if kind == dnsmessage.TypeA && !n.has4 || kind == dnsmessage.TypeAAAA && !n.has6 {
			continue
		}
		remaining++
		go func() {
			var connection net.Conn
			defer func() { results <- result{connection} }()
			for _, resolver := range n.dns {
				found, err := n.lookup(ctx, resolver, host, kind, 0)
				if err == nil && len(found) > 0 {
					connection = n.dialCandidates(ctx, found, uint16(port))
					return
				}
				if ctx.Err() != nil {
					return
				}
			}
		}()
	}
	var winner net.Conn
	for remaining > 0 {
		candidate := <-results
		remaining--
		if candidate.connection != nil {
			if winner == nil {
				winner = candidate.connection
				cancel()
			} else {
				candidate.connection.Close()
			}
		}
	}
	// All losing DNS/dial workers have stopped before the parent dial returns,
	// so network.Close can safely wait for its tracked lifetime operations.
	if winner != nil {
		return winner, nil
	}
	return nil, errConnect
}

// Queries are fully qualified and sent only to configured tunnel DNS. There
// is no system resolver, /etc/hosts, search-domain or direct dial fallback.
func (n *packetNetwork) lookup(ctx context.Context, resolver netip.Addr, host string, kind dnsmessage.Type, depth int) ([]netip.Addr, error) {
	if depth >= 8 || !validHost(host) {
		return nil, errConnect
	}
	name, err := dnsmessage.NewName(host + ".")
	if err != nil {
		return nil, errConnect
	}
	var nonce [2]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, errConnect
	}
	id := binary.BigEndian.Uint16(nonce[:])
	question := dnsmessage.Question{Name: name, Type: kind, Class: dnsmessage.ClassINET}
	query, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: id, RecursionDesired: true}, Questions: []dnsmessage.Question{question}}).Pack()
	if err != nil {
		return nil, errConnect
	}
	response, err := n.exchangeDNS(ctx, resolver, query, false)
	if err != nil {
		return nil, errConnect
	}
	var message dnsmessage.Message
	if message.Unpack(response) != nil || message.ID != id || !message.Response || message.RCode != dnsmessage.RCodeSuccess ||
		len(message.Questions) != 1 || message.Questions[0] != question {
		return nil, errConnect
	}
	if message.Truncated {
		response, err = n.exchangeDNS(ctx, resolver, query, true)
		if err != nil || message.Unpack(response) != nil || message.ID != id || !message.Response || message.Truncated ||
			message.RCode != dnsmessage.RCodeSuccess || len(message.Questions) != 1 || message.Questions[0] != question {
			return nil, errConnect
		}
	}
	allowed := map[string]bool{strings.ToLower(name.String()): true}
	canonical := strings.ToLower(name.String())
	for hop := 0; hop < 8; hop++ {
		changed := false
		for _, answer := range message.Answers {
			alias, ok := answer.Body.(*dnsmessage.CNAMEResource)
			if ok && answer.Header.Class == dnsmessage.ClassINET && strings.ToLower(answer.Header.Name.String()) == canonical {
				target := strings.ToLower(alias.CNAME.String())
				if allowed[target] {
					return nil, errConnect
				}
				canonical = target
				allowed[target] = true
				changed = true
				break
			}
		}
		if !changed {
			break
		}
	}
	var result []netip.Addr
	for _, answer := range message.Answers {
		if answer.Header.Class != dnsmessage.ClassINET || !allowed[strings.ToLower(answer.Header.Name.String())] {
			continue
		}
		var address netip.Addr
		switch body := answer.Body.(type) {
		case *dnsmessage.AResource:
			if kind == dnsmessage.TypeA {
				address = netip.AddrFrom4(body.A)
			}
		case *dnsmessage.AAAAResource:
			if kind == dnsmessage.TypeAAAA {
				address = netip.AddrFrom16(body.AAAA)
			}
		}
		if address.IsValid() && !address.IsUnspecified() && !address.Is4In6() {
			result = append(result, address)
			if len(result) > packetDNSAddressLimit {
				return nil, errConnect
			}
		}
	}
	if len(result) == 0 && canonical != strings.ToLower(name.String()) {
		return n.lookup(ctx, resolver, strings.TrimSuffix(canonical, "."), kind, depth+1)
	}
	if len(result) == 0 {
		return nil, errConnect
	}
	return result, nil
}

func (n *packetNetwork) exchangeDNS(ctx context.Context, resolver netip.Addr, query []byte, tcp bool) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var connection net.Conn
	var err error
	if tcp {
		connection, err = n.dialTCP(ctx, resolver, 53)
	} else {
		full, protocol := packetAddress(resolver, 53)
		connection, err = gonet.DialUDP(n.stack, nil, &full, protocol)
	}
	if err != nil {
		return nil, errConnect
	}
	defer connection.Close()
	stop := context.AfterFunc(ctx, func() { connection.Close() })
	defer stop()
	deadline, _ := ctx.Deadline()
	connection.SetDeadline(deadline)
	if !tcp {
		if _, err := connection.Write(query); err != nil {
			return nil, errConnect
		}
		buffer := make([]byte, 512)
		count, err := connection.Read(buffer)
		if err != nil {
			return nil, errConnect
		}
		return buffer[:count], nil
	}
	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if count, err := connection.Write(frame); err != nil || count != len(frame) {
		return nil, errConnect
	}
	var length [2]byte
	if _, err := io.ReadFull(connection, length[:]); err != nil {
		return nil, errConnect
	}
	size := int(binary.BigEndian.Uint16(length[:]))
	if size < 12 || size > 8192 {
		return nil, errConnect
	}
	response := make([]byte, size)
	if _, err := io.ReadFull(connection, response); err != nil {
		return nil, errConnect
	}
	return response, nil
}
