package routebridge

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

type refusalLog struct {
	mu      sync.Mutex
	records []refusalRecord
}

func (l *refusalLog) Refused(reason, host string, port, count int, collapsed bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.records = append(l.records, refusalRecord{refusalKey{reason, host, port}, count, collapsed})
}

func (l *refusalLog) take() []refusalRecord {
	l.mu.Lock()
	defer l.mu.Unlock()
	records := l.records
	l.records = nil
	return records
}

func refusalRoute(t *testing.T) (*Route, *refusalLog) {
	t.Helper()
	r, err := newRoute(localUser, localPassword, localRealm, "example.test", func(context.Context, string) (net.Conn, error) { return nil, errConnect }, nil)
	if err != nil {
		t.Fatal(err)
	}
	log := &refusalLog{}
	r.SetRefusalListener(log)
	return r, log
}

func sendRaw(t *testing.T, r *Route, data string) string {
	t.Helper()
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()), time.Second)
	if err != nil {
		t.Fatal("Local dial failed")
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	io.WriteString(c, data)
	c.(*net.TCPConn).CloseWrite()
	response, _ := io.ReadAll(c)
	return string(response)
}

func TestEveryRefusalReasonIsReportedOnceWithoutSecrets(t *testing.T) {
	r, log := refusalRoute(t)
	defer r.Close()
	secretAuth := "Proxy-Authorization: Basic c2VjcmV0LXVzZXI6c2VjcmV0LXBhc3N3b3Jk\r\n"
	for _, test := range []struct {
		send   string
		status string
		want   refusalRecord
	}{
		{"CONNECT evil.test:443 HTTP/1.1\r\nHost: evil.test:443\r\n" + localAuth() + "\r\n", "403", refusalRecord{refusalKey{refusalNotAllowed, "evil.test", 443}, 1, false}},
		{"CONNECT EVIL.Test:443 HTTP/1.1\r\nHost: EVIL.Test:443\r\n" + localAuth() + "\r\n", "403", refusalRecord{refusalKey{refusalNotAllowed, "evil.test", 443}, 1, false}},
		{"CONNECT 192.0.2.7:443 HTTP/1.1\r\nHost: 192.0.2.7:443\r\n" + localAuth() + "\r\n", "403", refusalRecord{refusalKey{refusalNotAllowed, "192.0.2.7", 443}, 1, false}},
		{"CONNECT example.test:80 HTTP/1.1\r\nHost: example.test:80\r\n" + localAuth() + "\r\n", "403", refusalRecord{refusalKey{refusalBadPort, "example.test", 80}, 1, false}},
		{"CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n" + secretAuth + "\r\n", "407", refusalRecord{refusalKey{refusalAuth, "example.test", 443}, 1, false}},
		{"CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n" + localAuth() + localAuth() + "\r\n", "407", refusalRecord{refusalKey{refusalAuth, "example.test", 443}, 1, false}},
		{"CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n", "407", refusalRecord{refusalKey{refusalAuthChallenge, "example.test", 443}, 1, false}},
		{"GET https://example.test/secret-path?token=secret-token HTTP/1.1\r\nHost: example.test\r\n" + secretAuth + "\r\n", "400", refusalRecord{refusalKey{refusalMalformed, "", 0}, 1, false}},
		{"CONNECT secret-token/path:443 HTTP/1.1\r\nHost: secret-token/path:443\r\n" + localAuth() + "\r\n", "403", refusalRecord{refusalKey{refusalMalformed, "", 0}, 1, false}},
	} {
		response := sendRaw(t, r, test.send)
		if !strings.HasPrefix(response, "HTTP/1.1 "+test.status) {
			t.Fatalf("Wrong refusal status: %q", strings.SplitN(response, "\r\n", 2)[0])
		}
		// Each case must be a first report; the reporter dedupes by reason/host/port.
		r.refusals.mu.Lock()
		r.refusals.entries = nil
		r.refusals.mu.Unlock()
		records := log.take()
		if len(records) != 1 || records[0] != test.want {
			t.Fatalf("Wrong refusal records: %+v", records)
		}
		for _, private := range []string{"secret", "token", "path", localUser, localPassword, "Basic"} {
			if strings.Contains(fmt.Sprint(records), private) {
				t.Fatal("Refusal record carried request content")
			}
		}
	}
	// A truncated request line is malformed even when no response can be sent.
	sendRaw(t, r, "CONNECT example.test:443 HTTP/1.1\n\n")
	if records := log.take(); len(records) != 1 || records[0].key.reason != refusalMalformed || records[0].key.host != "" {
		t.Fatalf("Malformed headers were not reported: %+v", records)
	}
	// A socket that asked for nothing is not a refusal.
	sendRaw(t, r, "")
	if records := log.take(); len(records) != 0 {
		t.Fatalf("Empty socket reported: %+v", records)
	}
}

func TestCapacityRefusalIsReported(t *testing.T) {
	r, log := refusalRoute(t)
	defer r.Close()
	var idle []net.Conn
	defer func() {
		for _, c := range idle {
			c.Close()
		}
	}()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", r.Port()), time.Second)
		if err != nil {
			t.Fatal("Local dial failed")
		}
		idle = append(idle, c)
		if len(idle) > maxConnections {
			time.Sleep(10 * time.Millisecond)
			for _, record := range log.take() {
				if record.key != (refusalKey{reason: refusalCapacity}) {
					t.Fatalf("Unexpected refusal: %+v", record)
				}
				return
			}
		}
	}
	t.Fatal("Capacity refusal was not reported")
}

func TestRepeatedRefusalsCollapseIntoPeriodicSummaries(t *testing.T) {
	previous := refusalWindow
	refusalWindow = 200 * time.Millisecond
	defer func() { refusalWindow = previous }()
	r, log := refusalRoute(t)
	defer r.Close()
	refuse := "CONNECT evil.test:443 HTTP/1.1\r\nHost: evil.test:443\r\n" + localAuth() + "\r\n"
	for range 5 {
		sendRaw(t, r, refuse)
	}
	key := refusalKey{refusalNotAllowed, "evil.test", 443}
	if records := log.take(); len(records) != 1 || records[0] != (refusalRecord{key, 1, false}) {
		t.Fatalf("First refusal not reported at once: %+v", records)
	}
	time.Sleep(3 * refusalWindow)
	// Slow sends may span windows; every repeat is still counted in a summary.
	repeats := 0
	for _, record := range log.take() {
		if record.key != key || !record.collapsed {
			t.Fatalf("Repeat not summarized: %+v", record)
		}
		repeats += record.count
	}
	if repeats != 4 {
		t.Fatalf("Repeats lost: %d", repeats)
	}
	// After a quiet window the next refusal is reported at once again.
	time.Sleep(3 * refusalWindow)
	sendRaw(t, r, refuse)
	if records := log.take(); len(records) != 1 || records[0] != (refusalRecord{key, 1, false}) {
		t.Fatalf("Refusal after a quiet window not reported at once: %+v", records)
	}
}

func TestPendingRefusalSummaryIsFlushedOnClose(t *testing.T) {
	r, log := refusalRoute(t)
	refuse := "CONNECT example.test:8443 HTTP/1.1\r\nHost: example.test:8443\r\n" + localAuth() + "\r\n"
	for range 3 {
		sendRaw(t, r, refuse)
	}
	key := refusalKey{refusalBadPort, "example.test", 8443}
	if records := log.take(); len(records) != 1 || records[0] != (refusalRecord{key, 1, false}) {
		t.Fatalf("First refusal not reported at once: %+v", records)
	}
	r.Close()
	if records := log.take(); len(records) != 1 || records[0] != (refusalRecord{key, 2, true}) {
		t.Fatalf("Pending summary not flushed on close: %+v", records)
	}
	r.Close()
	if records := log.take(); len(records) != 0 {
		t.Fatalf("Repeated close reported again: %+v", records)
	}
}

func TestRefusalsBeforeListenerAndBeyondHostLimitAreCounted(t *testing.T) {
	var p refusalReporter
	p.refuse(refusalNotAllowed, "early.test", 443)
	p.refuse(refusalNotAllowed, "early.test", 443)
	log := &refusalLog{}
	p.setListener(log)
	if records := log.take(); len(records) != 1 || records[0] != (refusalRecord{refusalKey{refusalNotAllowed, "early.test", 443}, 2, false}) {
		t.Fatalf("Refusals before the listener were lost: %+v", records)
	}
	for i := range 2 * maxRefusalKeys {
		p.refuse(refusalNotAllowed, fmt.Sprintf("host%d.test", i), 443)
	}
	p.close()
	total := 0
	for _, record := range log.take() {
		total += record.count
	}
	if total != 2*maxRefusalKeys {
		t.Fatalf("Refusals beyond the host limit were lost: %d", total)
	}
	p.mu.Lock()
	tracked := len(p.entries)
	p.mu.Unlock()
	if tracked > maxRefusalKeys+1 {
		t.Fatalf("Tracked refusal keys are unbounded: %d", tracked)
	}
}

func TestRefusalHostKeepsOnlySafeNames(t *testing.T) {
	for input, want := range map[string]string{
		"Example.TEST":                  "example.test",
		"cdn.example.test":              "cdn.example.test",
		"192.0.2.1":                     "192.0.2.1",
		"2001:DB8::1":                   "2001:db8::1",
		"fe80::1%secret-zone":           "fe80::1",
		"example.test/secret?token=x":   "",
		"user:password@example.test":    "",
		"localhost":                     "",
		"":                              "",
		strings.Repeat("a.", 127) + "a": "",
		"exa mple.test":                 "",
	} {
		if got := refusalHost(input); got != want {
			t.Fatalf("Unsafe refusal host: %q gave %q", input, got)
		}
	}
	for input, want := range map[string]int{"443": 443, "80": 80, "0": 0, "65536": 0, "x": 0, "000443": 0, "": 0} {
		if got := refusalPort(input); got != want {
			t.Fatalf("Wrong refusal port: %q gave %d", input, got)
		}
	}
}
