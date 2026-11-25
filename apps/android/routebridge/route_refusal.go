package routebridge

import (
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

// RefusalListener receives every connection this Route refused, so the app
// can log it. Reason is one of NOT_ALLOWED, BAD_PORT, MALFORMED, AUTH,
// CAPACITY or AUTH_CHALLENGE (a 407 to a CONNECT without credentials, which a
// client may answer). Host is empty or a validated lowercase DNS name or canonical IP
// literal; port is 0 when unknown. No path, query, header or credential is
// ever passed. Count is the number of refusals this record covers. Collapsed
// marks a periodic summary of repeats of an already reported refusal.
// Callbacks must not reenter this Route's Close.
type RefusalListener interface {
	Refused(reason, host string, port, count int, collapsed bool)
}

const (
	refusalNotAllowed = "NOT_ALLOWED"
	refusalBadPort    = "BAD_PORT"
	refusalMalformed  = "MALFORMED"
	refusalAuth       = "AUTH"
	// Challenge reports a 407 to a CONNECT that carried no credentials.
	refusalAuthChallenge = "AUTH_CHALLENGE"
	refusalCapacity      = "CAPACITY"
)

// refusalWindow bounds how often one repeated refusal is summarized. Tests
// shorten it.
var refusalWindow = 30 * time.Second

// Distinct hosts tracked per window; further ones collapse into a host-less
// record for their reason, so a counted refusal is never dropped.
const maxRefusalKeys = 32

type refusalKey struct {
	reason string
	host   string
	port   int
}

type refusalEntry struct {
	reported bool
	pending  int
}

type refusalRecord struct {
	key       refusalKey
	count     int
	collapsed bool
}

// refusalReporter reports the first refusal of a kind at once and collapses
// identical repeats into one summary per window. Pending counts are delivered
// when a listener is set, on each window and on close; nothing is discarded.
type refusalReporter struct {
	mu       sync.Mutex
	listener RefusalListener
	entries  map[refusalKey]*refusalEntry
	timer    *time.Timer
	closed   bool
}

// refusalHost returns the requested host only in a form that cannot carry a
// path, query or credential; anything else is omitted.
func refusalHost(host string) string {
	if len(host) > 253 {
		return ""
	}
	host = strings.ToLower(host)
	if address, err := netip.ParseAddr(host); err == nil {
		return address.WithZone("").String()
	}
	if hostPattern.MatchString(host) {
		return host
	}
	return ""
}

func refusalPort(port string) int {
	value, err := strconv.Atoi(port)
	if err != nil || len(port) > 5 || value < 1 || value > 65535 {
		return 0
	}
	return value
}

func (p *refusalReporter) refuse(reason, host string, port int) {
	p.mu.Lock()
	if p.entries == nil {
		p.entries = make(map[refusalKey]*refusalEntry)
	}
	key := refusalKey{reason, host, port}
	entry := p.entries[key]
	if entry == nil && len(p.entries) >= maxRefusalKeys {
		key = refusalKey{reason: reason}
		entry = p.entries[key]
	}
	if entry == nil {
		entry = &refusalEntry{}
		p.entries[key] = entry
	}
	var records []refusalRecord
	if !entry.reported && entry.pending == 0 && p.listener != nil {
		entry.reported = true
		records = append(records, refusalRecord{key: key, count: 1})
	} else {
		entry.pending++
	}
	if p.closed {
		records = append(records, p.drain(false)...)
	} else if p.timer == nil {
		p.timer = time.AfterFunc(refusalWindow, p.tick)
	}
	listener := p.listener
	p.mu.Unlock()
	deliver(listener, records)
}

// drain collects pending counts while holding mu. With evict, quiet entries
// are forgotten so their next refusal is reported at once.
func (p *refusalReporter) drain(evict bool) []refusalRecord {
	if p.listener == nil {
		return nil
	}
	var records []refusalRecord
	for key, entry := range p.entries {
		if entry.pending > 0 {
			records = append(records, refusalRecord{key: key, count: entry.pending, collapsed: entry.reported})
			entry.reported = true
			entry.pending = 0
		} else if evict {
			delete(p.entries, key)
		}
	}
	return records
}

func (p *refusalReporter) tick() {
	p.mu.Lock()
	p.timer = nil
	if p.closed {
		p.mu.Unlock()
		return
	}
	records := p.drain(true)
	if len(p.entries) > 0 {
		p.timer = time.AfterFunc(refusalWindow, p.tick)
	}
	listener := p.listener
	p.mu.Unlock()
	deliver(listener, records)
}

func (p *refusalReporter) setListener(listener RefusalListener) {
	p.mu.Lock()
	p.listener = listener
	records := p.drain(false)
	p.mu.Unlock()
	deliver(listener, records)
}

// close delivers every pending count. Later refusals are delivered at once.
func (p *refusalReporter) close() {
	p.mu.Lock()
	p.closed = true
	if p.timer != nil {
		p.timer.Stop()
		p.timer = nil
	}
	records := p.drain(false)
	listener := p.listener
	p.mu.Unlock()
	deliver(listener, records)
}

func deliver(listener RefusalListener, records []refusalRecord) {
	for _, record := range records {
		listener.Refused(record.key.reason, record.key.host, record.key.port, record.count, record.collapsed)
	}
}

// SetRefusalListener receives this Route's refusals, including any recorded
// before it was set.
func (r *Route) SetRefusalListener(listener RefusalListener) {
	r.refusals.setListener(listener)
}
