package routebridge

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
)

func TestPacketDNSTCPFallbackCNAMEAndResponseBinding(t *testing.T) {
	for _, mode := range []string{"cname-tcp", "wrong-id", "wrong-question", "unrelated-answer", "cname-cycle"} {
		t.Run(mode, func(t *testing.T) {
			a, b := packetSockets(t, unix.SOCK_DGRAM)
			client := fixturePacketBridge(t, a, "10.50.0.2", []netip.Addr{netip.MustParseAddr("10.50.0.1")})
			server := fixturePacketBridge(t, b, "10.50.0.1", nil)
			udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 53})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { udp.Close() })
			dnsTCP, err := server.network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.50.0.1"), Port: 53})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { dnsTCP.Close() })
			origin, err := server.network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.50.0.1"), Port: 443})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { origin.Close() })
			var tcpQueries atomic.Int32
			answer := func(query []byte, truncated bool) []byte {
				var request dnsmessage.Message
				if request.Unpack(query) != nil || len(request.Questions) != 1 {
					return nil
				}
				question := request.Questions[0]
				response := dnsmessage.Message{Header: dnsmessage.Header{ID: request.ID, Response: true, Truncated: truncated}, Questions: []dnsmessage.Question{question}}
				if !truncated {
					alias, _ := dnsmessage.NewName("alias.owned-route.test.")
					if mode == "wrong-id" {
						response.ID++
					}
					if mode == "wrong-question" {
						response.Questions[0].Name = alias
					}
					owner := question.Name
					if mode == "cname-tcp" || mode == "cname-cycle" {
						response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: owner, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET}, Body: &dnsmessage.CNAMEResource{CNAME: alias}})
						owner = alias
					}
					if mode == "cname-cycle" {
						response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: alias, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET}, Body: &dnsmessage.CNAMEResource{CNAME: question.Name}})
					}
					if mode == "unrelated-answer" {
						owner = alias
					}
					response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: owner, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{10, 50, 0, 1}}})
				}
				result, _ := response.Pack()
				return result
			}
			go func() {
				buffer := make([]byte, 512)
				for {
					count, address, err := udp.ReadFrom(buffer)
					if err != nil {
						return
					}
					udp.WriteTo(answer(buffer[:count], mode == "cname-tcp"), address)
				}
			}()
			go func() {
				connection, err := dnsTCP.Accept()
				if err != nil {
					return
				}
				defer connection.Close()
				connection.SetDeadline(time.Now().Add(3 * time.Second))
				var length [2]byte
				if _, err := io.ReadFull(connection, length[:]); err != nil {
					return
				}
				query := make([]byte, int(binary.BigEndian.Uint16(length[:])))
				if _, err := io.ReadFull(connection, query); err != nil {
					return
				}
				tcpQueries.Add(1)
				response := answer(query, false)
				binary.BigEndian.PutUint16(length[:], uint16(len(response)))
				connection.Write(length[:1])
				connection.Write(length[1:])
				connection.Write(response)
			}()
			go func() {
				connection, err := origin.Accept()
				if err == nil {
					connection.Close()
				}
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			connection, err := client.network.DialContext(ctx, "tcp", "owned-route.test:443")
			if mode == "cname-tcp" {
				if err != nil || tcpQueries.Load() != 1 {
					t.Fatal("Bound DNS TCP/CNAME fallback failed")
				}
				connection.Close()
			} else if err == nil {
				connection.Close()
				t.Fatal("Unbound/cyclic DNS answer accepted")
			}
		})
	}
}

func TestPacketDNSCloseCancelsPendingQuery(t *testing.T) {
	a, peer := packetSockets(t, unix.SOCK_DGRAM)
	client := fixturePacketBridge(t, a, "10.50.0.2", []netip.Addr{netip.MustParseAddr("10.50.0.1")})
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		connection, err := client.network.DialContext(context.Background(), "tcp", "owned-route.test:443")
		if err == nil {
			connection.Close()
			t.Error("Unanswered DNS accepted")
		}
	}()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	buffer := make([]byte, 1280)
	if _, err := peer.Read(buffer); err != nil {
		t.Fatal("No explicit DNS packet emitted")
	}
	client.Close()
	waitPacketClosed(t, finished)
}

func TestPacketDNSReachableIPv4DoesNotWaitForSilentAAAA(t *testing.T) {
	a, b := packetSockets(t, unix.SOCK_DGRAM)
	client, err := newPacketBridge(a, []netip.Addr{netip.MustParseAddr("10.50.0.2"), netip.MustParseAddr("fd50::2")}, []netip.Addr{netip.MustParseAddr("10.50.0.1")}, 1280)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(client.Close)
	server := fixturePacketBridge(t, b, "10.50.0.1", nil)
	udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	origin, err := server.network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.50.0.1"), Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { origin.Close() })
	aaaaSeen := make(chan struct{})
	go func() {
		buffer := make([]byte, 512)
		for {
			count, peer, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			var request dnsmessage.Message
			if request.Unpack(buffer[:count]) != nil || len(request.Questions) != 1 {
				continue
			}
			if request.Questions[0].Type == dnsmessage.TypeAAAA {
				select {
				case <-aaaaSeen:
				default:
					close(aaaaSeen)
				}
				continue // Deliberately no response to this family.
			}
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: request.ID, Response: true}, Questions: request.Questions,
				Answers: []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: request.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{10, 50, 0, 1}}}}}
			packed, _ := response.Pack()
			go func() {
				// Require unanswered AAAA to be outstanding before returning A.
				select {
				case <-aaaaSeen:
					udp.WriteTo(packed, peer)
				case <-time.After(time.Second):
				}
			}()
		}
	}()
	go func() {
		connection, err := origin.Accept()
		if err == nil {
			defer connection.Close()
			connection.Write([]byte("usable-A"))
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	connection, err := client.network.DialContext(ctx, "tcp", "owned-route.test:443")
	if err != nil {
		t.Fatal("Usable A answer lost to pending AAAA deadline")
	}
	defer connection.Close()
	connection.SetReadDeadline(time.Now().Add(time.Second))
	marker := make([]byte, 8)
	if _, err := io.ReadFull(connection, marker); err != nil || string(marker) != "usable-A" {
		t.Fatal("IPv4 origin payload unavailable")
	}
}

func TestPacketDNSReachableLaterIPv4DoesNotWaitForSilentFirstAddress(t *testing.T) {
	testPacketDNSCandidateAnswers(t, []byte{99, 1}, true)
}

func TestPacketDNSCandidateAnswerBound(t *testing.T) {
	for _, count := range []int{16, 17} {
		t.Run(fmt.Sprintf("addresses-%d", count), func(t *testing.T) {
			addresses := make([]byte, count)
			for index := range addresses {
				addresses[index] = byte(99 + index)
			}
			addresses[count-1] = 1
			testPacketDNSCandidateAnswers(t, addresses, count == 16)
		})
	}
}

func TestPacketDNSFailedCandidatesReturnNil(t *testing.T) {
	testPacketDNSCandidateAnswers(t, []byte{99, 100}, false)
}

func testPacketDNSCandidateAnswers(t *testing.T, addresses []byte, reachable bool) {
	t.Helper()
	a, b := packetSockets(t, unix.SOCK_DGRAM)
	client := fixturePacketBridge(t, a, "10.50.0.2", []netip.Addr{netip.MustParseAddr("10.50.0.1")})
	server := fixturePacketBridge(t, b, "10.50.0.1", nil)
	udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.50.0.1"), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	origin, err := server.network.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.50.0.1"), Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { origin.Close() })
	go func() {
		buffer := make([]byte, 512)
		for {
			count, peer, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			var request dnsmessage.Message
			if request.Unpack(buffer[:count]) != nil || len(request.Questions) != 1 {
				continue
			}
			question := request.Questions[0]
			response := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: request.ID, Response: true})
			response.EnableCompression()
			response.StartQuestions()
			response.Question(question)
			response.StartAnswers()
			// The owned server stack drops packets to unassigned .99, without
			// returning a TCP refusal. Its assigned .1 has a reachable listener.
			for _, last := range addresses {
				response.AResource(dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET}, dnsmessage.AResource{A: [4]byte{10, 50, 0, last}})
			}
			packed, _ := response.Finish()
			if len(packed) > 512 {
				t.Error("Owned address-bound fixture exceeded UDP response buffer")
				return
			}
			udp.WriteTo(packed, peer)
		}
	}()
	go func() {
		connection, err := origin.Accept()
		if err == nil {
			defer connection.Close()
			connection.Write([]byte("later-A"))
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	started := time.Now()
	connection, err := client.network.DialContext(ctx, "tcp", "owned-route.test:443")
	if !reachable {
		if connection != nil || err == nil {
			if connection != nil {
				connection.Close()
			}
			t.Fatal("Rejected or failed DNS candidates returned a connection")
		}
		closed := make(chan struct{})
		go func() { client.Close(); close(closed) }()
		waitPacketClosed(t, closed)
		return
	}
	if err != nil {
		t.Fatalf("Reachable later A answer lost to silent first address after %s", time.Since(started))
	}
	defer connection.Close()
	connection.SetReadDeadline(time.Now().Add(time.Second))
	marker := make([]byte, 7)
	if _, err := io.ReadFull(connection, marker); err != nil || string(marker) != "later-A" {
		t.Fatal("Later IPv4 origin payload unavailable")
	}
}
