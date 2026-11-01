// Command route-trial is a bounded development diagnostic, not a deployed web
// companion. Private configuration arrives on stdin; diagnostics never echo it.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	"net.fstab.tachiai/routebridge"
)

const maximumLine = 32768

var credential = regexp.MustCompile(`^[A-Za-z0-9_-]{16,128}$`)

type configuration struct {
	Profile      string
	Username     string
	Password     string
	Realm        string
	AllowedHosts string
	LeaseSeconds int
}

func decodeConfiguration(raw []byte) (configuration, bool) {
	var config configuration
	if len(raw) == 0 || len(raw) > maximumLine || !utf8.Valid(raw) {
		return config, false
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	if token, err := decoder.Token(); err != nil || token != json.Delim('{') {
		return config, false
	}
	seen := make(map[string]bool)
	for decoder.More() {
		token, err := decoder.Token()
		key, ok := token.(string)
		if err != nil || !ok || seen[key] {
			return configuration{}, false
		}
		seen[key] = true
		var target any
		switch key {
		case "profile":
			target = &config.Profile
		case "username":
			target = &config.Username
		case "password":
			target = &config.Password
		case "realm":
			target = &config.Realm
		case "allowedHosts":
			target = &config.AllowedHosts
		case "leaseSeconds":
			target = &config.LeaseSeconds
		default:
			return configuration{}, false
		}
		if decoder.Decode(target) != nil {
			return configuration{}, false
		}
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim('}') {
		return configuration{}, false
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF || len(seen) != 6 ||
		len(config.Profile) == 0 || len(config.Profile) > 8192 ||
		len(config.AllowedHosts) == 0 || len(config.AllowedHosts) > 4096 ||
		strings.ContainsRune(config.Profile, utf8.RuneError) || strings.ContainsRune(config.AllowedHosts, utf8.RuneError) ||
		!credential.MatchString(config.Username) || !credential.MatchString(config.Password) ||
		!credential.MatchString(config.Realm) || config.LeaseSeconds < 1 || config.LeaseSeconds > 900 {
		return configuration{}, false
	}
	return config, true
}

type trialRoute interface {
	Port() int
	Close() error
}

type factory func(configuration) (trialRoute, error)

type construction struct {
	route trialRoute
	err   error
	panic bool
}

func construct(makeRoute factory, config configuration) (result construction) {
	defer func() {
		if recover() != nil {
			result.panic = true
		}
	}()
	result.route, result.err = makeRoute(config)
	return result
}

func closeRoute(route trialRoute) (confirmed bool) {
	defer func() {
		if recover() != nil {
			confirmed = false
		}
	}()
	return route.Close() == nil
}

func port(route trialRoute) (value int) {
	defer func() {
		if recover() != nil {
			value = 0
		}
	}()
	return route.Port()
}

type bounds struct {
	input, startup, cleanup time.Duration
}

type inputLine struct {
	bytes []byte
	err   error
}

// Read one bounded line without waiting for stdin EOF. The parent keeps stdin
// open as its ownership signal; no subsequent bytes are configuration updates.
func readLine(reader *bufio.Reader) inputLine {
	raw, err := reader.ReadSlice('\n')
	if err == io.EOF && len(raw) == 0 {
		return inputLine{err: io.EOF}
	}
	if err != nil || len(raw) > maximumLine+1 {
		return inputLine{err: errors.New("input refused")}
	}
	return inputLine{bytes: bytes.Clone(raw[:len(raw)-1])}
}

type record struct {
	Phase   string `json:"phase"`
	Port    int    `json:"port,omitempty"`
	Reason  string `json:"reason,omitempty"`
	Cleanup string `json:"cleanup,omitempty"`
}

func run(ctx context.Context, input io.Reader, output io.Writer, makeRoute factory, limit bounds) int {
	encoder := json.NewEncoder(output)
	finish := func(reason string, confirmed bool) int {
		cleanup := "UNCONFIRMED"
		if confirmed {
			cleanup = "CONFIRMED"
		}
		err := encoder.Encode(record{Phase: "closed", Reason: reason, Cleanup: cleanup})
		if !confirmed {
			return 2
		}
		if err != nil || (reason != "STOP" && reason != "EOF" && reason != "SIGNAL" && reason != "LEASE") {
			return 1
		}
		return 0
	}
	reader := bufio.NewReaderSize(input, maximumLine+2)
	initial := make(chan inputLine, 1)
	go func() { initial <- readLine(reader) }()
	inputEnd := time.Now().Add(limit.input)
	inputTimer := time.NewTimer(limit.input)
	defer inputTimer.Stop()
	var first inputLine
	select {
	case first = <-initial:
	case <-inputTimer.C:
		return finish("INPUT_TIMEOUT", true)
	case <-ctx.Done():
		return finish("SIGNAL", true)
	}
	if !time.Now().Before(inputEnd) {
		return finish("INPUT_TIMEOUT", true)
	}
	if first.err != nil {
		return finish("INPUT_REJECTED", true)
	}
	config, accepted := decodeConfiguration(first.bytes)
	clear(first.bytes)
	if !accepted {
		return finish("INPUT_REJECTED", true)
	}
	leaseEnd := time.Now().Add(time.Duration(config.LeaseSeconds) * time.Second)
	lease := time.NewTimer(time.Until(leaseEnd))
	defer lease.Stop()
	control := make(chan string, 1)
	go func() {
		command := readLine(reader)
		reason := "INVALID_CONTROL"
		if command.err != nil {
			// EOF is separately detected without inspecting/echoing partial input.
			if errors.Is(command.err, io.EOF) {
				reason = "EOF"
			}
		} else if string(command.bytes) == "stop" {
			reason = "STOP"
		}
		clear(command.bytes)
		control <- reason
	}()
	if encoder.Encode(record{Phase: "starting"}) != nil {
		return finish("OUTPUT_FAILED", true)
	}
	results := make(chan construction)
	abandoned := make(chan struct{})
	defer close(abandoned)
	startupEnd := time.Now().Add(limit.startup)
	go func() {
		result := construct(makeRoute, config)
		select {
		case results <- result:
		case <-abandoned:
			// A late constructor result still has an owner. The earlier timeout
			// receipt remains unconfirmed even if this later close succeeds.
			if result.route != nil {
				closeRoute(result.route)
			}
		}
	}()
	shutdown := func(reason string, known *construction) int {
		cleanupEnd := time.Now().Add(limit.cleanup)
		deadline := time.NewTimer(time.Until(cleanupEnd))
		defer deadline.Stop()
		var built construction
		if known == nil {
			select {
			case built = <-results:
			case <-deadline.C:
				return finish(reason, false)
			}
		} else {
			built = *known
		}
		if !time.Now().Before(cleanupEnd) {
			return finish(reason, false)
		}
		if built.panic {
			return finish(reason, false)
		}
		if built.route == nil {
			return finish(reason, true)
		}
		closed := make(chan bool, 1)
		go func() { closed <- closeRoute(built.route) }()
		select {
		case confirmed := <-closed:
			return finish(reason, confirmed && time.Now().Before(cleanupEnd))
		case <-deadline.C:
			return finish(reason, false)
		}
	}
	startup := time.NewTimer(time.Until(startupEnd))
	defer startup.Stop()
	var built construction
	select {
	case built = <-results:
	case reason := <-control:
		return shutdown(reason, nil)
	case <-ctx.Done():
		return shutdown("SIGNAL", nil)
	case <-lease.C:
		return shutdown("LEASE", nil)
	case <-startup.C:
		return shutdown("STARTUP_TIMEOUT", nil)
	}
	if !time.Now().Before(startupEnd) {
		return shutdown("STARTUP_TIMEOUT", &built)
	}
	if built.panic || built.err != nil || built.route == nil {
		return shutdown("STARTUP_FAILED", &built)
	}
	// Do not publish readiness for an already observed owner cancellation.
	select {
	case reason := <-control:
		return shutdown(reason, &built)
	case <-ctx.Done():
		return shutdown("SIGNAL", &built)
	case <-lease.C:
		return shutdown("LEASE", &built)
	default:
	}
	if !time.Now().Before(leaseEnd) {
		return shutdown("LEASE", &built)
	}
	number := port(built.route)
	if number < 1 || number > 65535 {
		return shutdown("STARTUP_FAILED", &built)
	}
	if encoder.Encode(record{Phase: "ready", Port: number}) != nil {
		return shutdown("OUTPUT_FAILED", &built)
	}
	select {
	case reason := <-control:
		return shutdown(reason, &built)
	case <-ctx.Done():
		return shutdown("SIGNAL", &built)
	case <-lease.C:
		return shutdown("LEASE", &built)
	}
}

func main() {
	if len(os.Args) != 1 {
		json.NewEncoder(os.Stdout).Encode(record{Phase: "closed", Reason: "INPUT_REJECTED", Cleanup: "CONFIRMED"})
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := run(ctx, os.Stdin, os.Stdout, func(config configuration) (trialRoute, error) {
		route, err := routebridge.NewWireGuard(config.Profile, config.Username, config.Password, config.Realm, config.AllowedHosts)
		if route == nil {
			return nil, err
		}
		return route, err
	}, bounds{input: 5 * time.Second, startup: 20 * time.Second, cleanup: 5 * time.Second})
	stop()
	// Blocked constructors/closers remain owned by this process until exit. Their
	// unconfirmed receipt is never upgraded to a completed cleanup claim.
	os.Exit(code)
}
