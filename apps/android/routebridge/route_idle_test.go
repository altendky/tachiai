package routebridge

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

// Media downloads keep only one direction busy; the tunnel must outlive the
// idle bound while either side progresses and still close once both stall.
func TestTunnelIdleTimeoutExtendsOnProgressAndClosesWhenIdle(t *testing.T) {
	previous := tunnelIdleTimeout
	tunnelIdleTimeout = 400 * time.Millisecond
	defer func() { tunnelIdleTimeout = previous }()

	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	streaming := make(chan bool, 2)
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn, stream bool) {
				defer c.Close()
				if !stream {
					io.Copy(io.Discard, c)
					return
				}
				for i := 0; i < 30; i++ {
					if _, err := c.Write([]byte{byte(i)}); err != nil {
						return
					}
					time.Sleep(50 * time.Millisecond)
				}
			}(c, <-streaming)
		}
	}()
	r, err := newRoute(localUser, localPassword, localRealm, "example.test", func(ctx context.Context, target string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp4", listener.Addr().String())
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()

	streaming <- true
	c, reader, status := connect(t, r, "example.test:443", localAuth())
	if status != 200 {
		t.Fatalf("Unexpected status %d", status)
	}
	began := time.Now()
	received, _ := io.ReadAll(reader)
	c.Close()
	if len(received) != 30 {
		t.Fatalf("Active tunnel cut after %v with %d bytes", time.Since(began), len(received))
	}
	if time.Since(began) < 3*tunnelIdleTimeout {
		t.Fatal("Fixture did not outlast the idle bound")
	}

	streaming <- false
	c, reader, status = connect(t, r, "example.test:443", localAuth())
	if status != 200 {
		t.Fatalf("Unexpected status %d", status)
	}
	began = time.Now()
	io.ReadAll(reader)
	c.Close()
	if elapsed := time.Since(began); elapsed < tunnelIdleTimeout/2 || elapsed > 5*tunnelIdleTimeout {
		t.Fatalf("Idle tunnel closed after %v", elapsed)
	}
}
