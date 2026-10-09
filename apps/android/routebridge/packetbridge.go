package routebridge

import (
	"encoding/binary"
	"net"
	"net/netip"
	"sync"

	"golang.org/x/sys/unix"
)

// packetBridge is an unregistered userspace packet transport. Its datagram
// socket is owned here; it installs no device, system routes or DNS resolver.
// The native tunnel peer must preserve one complete raw IP packet per datagram.
type packetBridge struct {
	network                         *packetNetwork
	packets                         *net.UnixConn
	stopOnce                        sync.Once
	inboundDone, outboundDone, done chan struct{}
}

// newPacketBridge consumes packets even when configuration is rejected. DNS is
// explicit; an empty list is useful for numeric-only peers, never host fallback.
func newPacketBridge(packets *net.UnixConn, addresses, dns []netip.Addr, mtu int) (*packetBridge, error) {
	if packets == nil {
		return nil, errConfig
	}
	valid := mtu >= 576 && mtu <= 9000 && len(addresses) > 0 && len(addresses) <= 16 && len(dns) <= 16
	raw, err := packets.SyscallConn()
	if err != nil {
		packets.Close()
		return nil, errConfig
	}
	if err := raw.Control(func(fd uintptr) {
		kind, err := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_TYPE)
		valid = valid && err == nil && kind == unix.SOCK_DGRAM
	}); err != nil {
		valid = false
	}
	seen := make(map[netip.Addr]bool)
	for _, address := range addresses {
		valid = valid && !seen[address]
		seen[address] = true
	}
	for _, address := range append(append([]netip.Addr(nil), addresses...), dns...) {
		valid = valid && address.IsValid() && !address.IsUnspecified() && !address.Is4In6() && address.Zone() == "" && (!address.Is6() || mtu >= 1280)
	}
	for _, resolver := range dns {
		family := false
		for _, address := range addresses {
			family = family || address.Is4() == resolver.Is4()
		}
		valid = valid && family
	}
	if !valid {
		packets.Close()
		return nil, errConfig
	}
	network, err := newPacketNetwork(addresses, dns, mtu)
	if err != nil {
		packets.Close()
		return nil, errConnect
	}
	b := &packetBridge{network: network, packets: packets, inboundDone: make(chan struct{}), outboundDone: make(chan struct{}), done: make(chan struct{})}
	go b.receive(mtu)
	go b.send(mtu)
	return b, nil
}

// Truncation and malformed framing are fatal. Feeding an incomplete datagram
// to the IP stack, or concatenating packets, would hide transport corruption.
func completeIPPacket(packet []byte) bool {
	if len(packet) == 0 {
		return false
	}
	switch packet[0] >> 4 {
	case 4:
		return len(packet) >= 20 && int(packet[0]&15)*4 >= 20 && int(packet[0]&15)*4 <= len(packet) && int(binary.BigEndian.Uint16(packet[2:4])) == len(packet)
	case 6:
		return len(packet) >= 40 && int(binary.BigEndian.Uint16(packet[4:6]))+40 == len(packet)
	default:
		return false
	}
}

func (b *packetBridge) receive(mtu int) {
	defer close(b.inboundDone)
	defer b.stop()
	buffer := make([]byte, mtu)
	for {
		n, _, flags, _, err := b.packets.ReadMsgUnix(buffer, nil)
		if err != nil || flags&unix.MSG_TRUNC != 0 || !completeIPPacket(buffer[:n]) {
			return
		}
		b.network.writePacket(buffer[:n])
	}
}

func (b *packetBridge) send(mtu int) {
	defer close(b.outboundDone)
	defer b.stop()
	buffer := make([]byte, mtu)
	for {
		count, err := b.network.readPacket(buffer)
		if err != nil {
			return
		}
		if !completeIPPacket(buffer[:count]) {
			return
		}
		n, err := b.packets.Write(buffer[:count])
		if err != nil || n != count {
			return
		}
	}
}

func (b *packetBridge) stop() {
	b.stopOnce.Do(func() {
		b.packets.Close() // Interrupt blocked reads and backpressured writes.
		go func() {
			<-b.inboundDone // No packet injection can race device teardown.
			b.network.Close()
			<-b.outboundDone
			close(b.done)
		}()
	})
}

func (b *packetBridge) Close() { b.stop(); <-b.done }
