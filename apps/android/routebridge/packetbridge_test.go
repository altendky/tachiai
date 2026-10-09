package routebridge

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
)

func packetSockets(t *testing.T, kind int) (*net.UnixConn, *net.UnixConn) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, kind|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	connections := make([]*net.UnixConn, 2)
	for i, fd := range fds {
		file := os.NewFile(uintptr(fd), "owned-packet-fixture")
		connection, err := net.FileConn(file)
		file.Close() // FileConn owns a duplicate, not the original descriptor.
		if err != nil {
			t.Fatal(err)
		}
		connections[i] = connection.(*net.UnixConn)
		t.Cleanup(func() { connection.Close() })
	}
	return connections[0], connections[1]
}

func fixturePacketBridge(t *testing.T, packets *net.UnixConn, address string, dns []netip.Addr) *packetBridge {
	t.Helper()
	b, err := newPacketBridge(packets, []netip.Addr{netip.MustParseAddr(address)}, dns, 1280)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.Close)
	return b
}

func waitPacketClosed(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Packet bridge did not stop")
	}
}

func servePacketDNS(t *testing.T, b *packetBridge, queries *atomic.Int32) {
	t.Helper()
	udp, err := b.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, address, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			var parser dnsmessage.Parser
			header, err := parser.Start(buffer[:n])
			if err != nil {
				continue
			}
			question, err := parser.Question()
			if err != nil {
				continue
			}
			queries.Add(1)
			builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: header.ID, Response: true, RecursionAvailable: true})
			builder.StartQuestions()
			builder.Question(question)
			builder.StartAnswers()
			if question.Type == dnsmessage.TypeA && question.Name.String() == "owned-route.test." {
				builder.AResource(dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 1}, dnsmessage.AResource{A: [4]byte{10, 50, 0, 1}})
			}
			reply, err := builder.Finish()
			if err == nil {
				udp.WriteTo(reply, address)
			}
		}
	}()
}

// Reusing the same virtual addresses in independent pairs proves the packet
// stacks and their DNS/TCP connections do not use shared OS interfaces/routes.
func TestPacketBridgeTCPDNSAndIsolation(t *testing.T) {
	var clients []*packetBridge
	var queries [2]atomic.Int32
	for index := range 2 {
		a, b := packetSockets(t, unix.SOCK_DGRAM)
		client := fixturePacketBridge(t, a, "10.50.0.2", []netip.Addr{netip.MustParseAddr("10.50.0.1")})
		server := fixturePacketBridge(t, b, "10.50.0.1", nil)
		servePacketDNS(t, server, &queries[index])
		listener, err := server.network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.50.0.1"), Port: 443})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { listener.Close() })
		go func() {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			defer connection.Close()
			connection.SetDeadline(time.Now().Add(3 * time.Second))
			connection.Write([]byte{byte(index)})
			io.Copy(connection, connection)
		}()
		clients = append(clients, client)
	}
	for index, client := range clients {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		connection, err := client.network.DialContext(ctx, "tcp", "owned-route.test:443")
		cancel()
		if err != nil {
			t.Fatal("Explicit tunnel DNS/TCP failed:", err)
		}
		connection.SetDeadline(time.Now().Add(3 * time.Second))
		marker := make([]byte, 1)
		if _, err := io.ReadFull(connection, marker); err != nil || marker[0] != byte(index) {
			t.Fatal("Stack identity mixed")
		}
		payload := bytes.Repeat([]byte{byte(index + 1)}, 16000)
		if _, err := connection.Write(payload); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, len(payload))
		if _, err := io.ReadFull(connection, reply); err != nil || !bytes.Equal(reply, payload) {
			t.Fatal("TCP packet payload changed")
		}
		connection.Close()
		if queries[index].Load() == 0 {
			t.Fatal("DNS bypassed explicit tunnel resolver")
		}
	}
	clients[0].Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := clients[0].network.DialContext(ctx, "tcp", "owned-route.test:443"); err == nil {
		t.Fatal("Closed stack accepted connection")
	}
	select {
	case <-clients[1].done:
		t.Fatal("One stack close stopped another")
	default:
	}
}

func TestPacketBridgeDatagramFraming(t *testing.T) {
	a, b := packetSockets(t, unix.SOCK_DGRAM)
	client := fixturePacketBridge(t, a, "10.50.0.2", nil)
	server := fixturePacketBridge(t, b, "10.50.0.1", nil)
	udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 9000})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	udp.SetDeadline(time.Now().Add(3 * time.Second))
	sender, err := client.network.DialUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.2")}, &net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 9000})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	sender.SetDeadline(time.Now().Add(3 * time.Second))
	for _, size := range []int{1, 117, 999, 2, 1252} {
		payload := bytes.Repeat([]byte{byte(size)}, size)
		if _, err := sender.Write(payload); err != nil {
			t.Fatal(err)
		}
		buffer := make([]byte, 2000)
		n, _, err := udp.ReadFrom(buffer)
		if err != nil || !bytes.Equal(buffer[:n], payload) {
			t.Fatal("Datagram boundaries changed")
		}
	}
}

func TestPacketBridgeRejectsCorruptOrTruncatedDatagram(t *testing.T) {
	valid := make([]byte, 20)
	valid[0] = 0x45
	binary.BigEndian.PutUint16(valid[2:4], 20)
	large := make([]byte, 1281)
	large[0] = 0x45
	binary.BigEndian.PutUint16(large[2:4], 1281)
	for _, packet := range [][]byte{nil, {0x45}, append(append([]byte(nil), valid...), valid...), large} {
		a, peer := packetSockets(t, unix.SOCK_DGRAM)
		bridge := fixturePacketBridge(t, a, "10.50.0.2", nil)
		if _, err := peer.Write(packet); err != nil {
			t.Fatal(err)
		}
		waitPacketClosed(t, bridge.done)
	}
}

func TestPacketBridgeIPv6Packets(t *testing.T) {
	a, b := packetSockets(t, unix.SOCK_DGRAM)
	client := fixturePacketBridge(t, a, "fd50::2", nil)
	server := fixturePacketBridge(t, b, "fd50::1", nil)
	udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("fd50::1"), Port: 9000})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	udp.SetDeadline(time.Now().Add(3 * time.Second))
	sender, err := client.network.DialUDP(&net.UDPAddr{IP: net.ParseIP("fd50::2")}, &net.UDPAddr{IP: net.ParseIP("fd50::1"), Port: 9000})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	payload := []byte("owned-ipv6-packet")
	if _, err := sender.Write(payload); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 1280)
	n, _, err := udp.ReadFrom(buffer)
	if err != nil || !bytes.Equal(buffer[:n], payload) {
		t.Fatal("IPv6 packet transfer failed")
	}
}

func TestPacketBridgeNoDNSFallbackAndClosePendingTCP(t *testing.T) {
	a, peer := packetSockets(t, unix.SOCK_DGRAM)
	bridge := fixturePacketBridge(t, a, "10.50.0.2", nil)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := bridge.network.DialContext(ctx, "tcp", "owned-route.test:443"); err == nil {
		t.Fatal("Missing tunnel DNS accepted hostname")
	}
	dialDone := make(chan struct{})
	go func() {
		defer close(dialDone)
		connection, err := bridge.network.DialContext(ctx, "tcp", "10.50.0.1:443")
		if err == nil {
			connection.Close()
			t.Error("Unanswered tunnel TCP connected")
		}
	}()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	buffer := make([]byte, 1280)
	if _, err := peer.Read(buffer); err != nil {
		t.Fatal("Pending TCP emitted no tunnel packet")
	}
	bridge.Close()
	waitPacketClosed(t, dialDone)
}

func TestPacketBridgeBackpressureAndConcurrentClose(t *testing.T) {
	a, peer := packetSockets(t, unix.SOCK_DGRAM)
	a.SetWriteBuffer(2048)
	peer.SetReadBuffer(2048)
	bridge := fixturePacketBridge(t, a, "10.50.0.2", nil)
	sender, err := bridge.network.DialUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.2")}, &net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 9000})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	var writes atomic.Int32
	producerDone := make(chan struct{})
	go func() {
		defer close(producerDone)
		payload := make([]byte, 1000)
		for {
			select {
			case <-bridge.done:
				return
			default:
			}
			sender.Write(payload)
			writes.Add(1)
			runtime.Gosched()
		}
	}()
	// Wait for the unread native-side socket to block the pump and fill the
	// bounded SDK packet queue. Keep writing through teardown to expose races.
	deadline := time.Now().Add(2 * time.Second)
	for {
		if writes.Load() > 128 && bridge.network.endpoint.NumQueued() == 128 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("Fixture did not exercise backpressure")
		}
		time.Sleep(time.Millisecond)
	}
	var callers sync.WaitGroup
	for range 8 {
		callers.Add(1)
		go func() { defer callers.Done(); bridge.Close() }()
	}
	closed := make(chan struct{})
	go func() { callers.Wait(); close(closed) }()
	waitPacketClosed(t, closed)
	waitPacketClosed(t, producerDone)
}

func TestPacketBridgeRejectsStreamSocketAndInvalidConfiguration(t *testing.T) {
	a, _ := packetSockets(t, unix.SOCK_STREAM)
	if bridge, err := newPacketBridge(a, []netip.Addr{netip.MustParseAddr("10.50.0.2")}, nil, 1280); err == nil || bridge != nil {
		t.Fatal("Stream socket accepted without packet framing")
	}
	for _, test := range []struct {
		addresses, dns []netip.Addr
		mtu            int
	}{
		{nil, nil, 1280}, {[]netip.Addr{netip.MustParseAddr("::1")}, nil, 576},
		{[]netip.Addr{netip.MustParseAddr("10.50.0.2")}, []netip.Addr{netip.MustParseAddr("::1")}, 1280},
		{[]netip.Addr{netip.MustParseAddr("10.50.0.2"), netip.MustParseAddr("10.50.0.2")}, nil, 1280},
	} {
		a, _ := packetSockets(t, unix.SOCK_DGRAM)
		if bridge, err := newPacketBridge(a, test.addresses, test.dns, test.mtu); err == nil || bridge != nil {
			t.Fatal("Invalid stack configuration accepted")
		}
	}
}
