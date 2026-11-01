package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const privateFixture = "PRIVATE_SYNTHETIC_CONFIGURATION"

func configurationLine(lease int) string {
	raw, _ := json.Marshal(map[string]any{"profile": privateFixture, "username": "fixture_username_1234",
		"password": "PRIVATE_password_1234", "realm": "fixture_realm_1234", "allowedHosts": "owned.test", "leaseSeconds": lease})
	return string(raw) + "\n"
}

func TestConfigurationRejectsAmbiguousUnknownMalformedOrUnboundedInput(t *testing.T) {
	valid := strings.TrimSuffix(configurationLine(1), "\n")
	if value, ok := decodeConfiguration([]byte(valid)); !ok || value.Profile != privateFixture || value.LeaseSeconds != 1 {
		t.Fatal("Valid private configuration refused")
	}
	for _, raw := range []string{
		"", "null", "[]", "{}", valid + valid, valid + " private",
		strings.TrimSuffix(valid, "}") + `,"profile":"second"}`,
		strings.TrimSuffix(valid, "}") + `,"\u0070rofile":"second"}`,
		strings.TrimSuffix(valid, "}") + `,"unknown":"PRIVATE"}`,
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":0`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":901`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":1.0`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":1e2`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":null`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":"1"`, 1),
		strings.Replace(valid, `"leaseSeconds":1`, `"leaseSeconds":{}`, 1),
		strings.Replace(valid, `"profile":"`+privateFixture+`"`, `"profile":null`, 1),
		strings.Replace(valid, privateFixture, `\ud800`, 1),
		strings.Replace(valid, "fixture_username_1234", "short", 1),
		strings.Replace(valid, "fixture_realm_1234", `realm_with_\n_control`, 1),
		strings.Replace(valid, privateFixture, strings.Repeat("p", 8193), 1),
		strings.Replace(valid, "owned.test", strings.Repeat("h", 4097), 1),
		strings.Repeat(" ", maximumLine+1) + valid,
		strings.Replace(valid, privateFixture, "\xff", 1),
	} {
		if _, ok := decodeConfiguration([]byte(raw)); ok {
			t.Fatal("Invalid private configuration was accepted")
		}
	}
}

type fakeRoute struct {
	portNumber int
	close      func() error
	calls      atomic.Int32
}

func (r *fakeRoute) Port() int { return r.portNumber }
func (r *fakeRoute) Close() error {
	r.calls.Add(1)
	if r.close != nil {
		return r.close()
	}
	return nil
}

type output struct {
	mu      sync.Mutex
	buffer  bytes.Buffer
	records chan record
}

func newOutput() *output { return &output{records: make(chan record, 8)} }
func (o *output) Write(raw []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	var value record
	if json.Unmarshal(raw, &value) != nil {
		return 0, errors.New("fixture protocol error")
	}
	o.buffer.Write(raw)
	o.records <- value
	return len(raw), nil
}
func (o *output) text() string {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.buffer.String()
}
func (o *output) phase(t *testing.T, phase string) record {
	t.Helper()
	for {
		select {
		case value := <-o.records:
			if value.Phase == phase {
				return value
			}
			if value.Phase == "closed" {
				t.Fatal("Runner closed before requested phase")
			}
		case <-time.After(3 * time.Second):
			t.Fatal("Fixture did not reach bounded phase")
		}
	}
}

type running struct {
	input  *io.PipeWriter
	output *output
	code   chan int
	cancel context.CancelFunc
}

func start(t *testing.T, makeRoute factory, limit bounds, lease int) *running {
	t.Helper()
	reader, writer := io.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	trial := &running{input: writer, output: newOutput(), code: make(chan int, 1), cancel: cancel}
	go func() { trial.code <- run(ctx, reader, trial.output, makeRoute, limit) }()
	t.Cleanup(func() { cancel(); writer.Close(); reader.Close() })
	if _, err := io.WriteString(writer, configurationLine(lease)); err != nil {
		t.Fatal("Could not deliver owned configuration")
	}
	return trial
}

func (r *running) ended(t *testing.T, reason, cleanup string, code int) {
	t.Helper()
	closed := r.output.phase(t, "closed")
	if closed.Reason != reason || closed.Cleanup != cleanup || closed.Port != 0 {
		t.Fatal("Incorrect closed receipt")
	}
	select {
	case actual := <-r.code:
		if actual != code {
			t.Fatal("Incorrect runner exit status")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Runner did not finish within fixture bound")
	}
	for _, private := range []string{privateFixture, "PRIVATE_password_1234", "fixture_username_1234", "fixture_realm_1234", "owned.test"} {
		if strings.Contains(r.output.text(), private) {
			t.Fatal("Private input reflected in output")
		}
	}
}

func ordinaryBounds() bounds {
	return bounds{input: time.Second, startup: time.Second, cleanup: time.Second}
}

func TestReadyDoesNotWaitForConfigEofAndEachOwnerEndClosesExactlyOnce(t *testing.T) {
	for _, reason := range []string{"STOP", "EOF", "SIGNAL", "LEASE", "INVALID_CONTROL"} {
		t.Run(reason, func(t *testing.T) {
			route := &fakeRoute{portNumber: 43210}
			lease := 10
			if reason == "LEASE" {
				lease = 1
			}
			trial := start(t, func(config configuration) (trialRoute, error) {
				if config.Profile != privateFixture || config.Password != "PRIVATE_password_1234" {
					t.Error("Private input was not passed intact to constructor")
				}
				return route, nil
			}, ordinaryBounds(), lease)
			if ready := trial.output.phase(t, "ready"); ready.Port != route.portNumber || ready.Reason != "" || ready.Cleanup != "" {
				t.Fatal("Incorrect readiness receipt")
			}
			switch reason {
			case "STOP":
				io.WriteString(trial.input, "stop\n")
			case "EOF":
				trial.input.Close()
			case "SIGNAL":
				trial.cancel()
			case "INVALID_CONTROL":
				io.WriteString(trial.input, "PRIVATE_CONTROL\n")
			}
			code := 0
			if reason == "INVALID_CONTROL" {
				code = 1
			}
			trial.ended(t, reason, "CONFIRMED", code)
			if route.calls.Load() != 1 || strings.Contains(trial.output.text(), "PRIVATE_CONTROL") {
				t.Fatal("Cleanup ownership or private control boundary failed")
			}
		})
	}
}

func TestInputTimeoutAndUnterminatedOrOversizedLineNeverConstruct(t *testing.T) {
	var calls atomic.Int32
	factory := func(configuration) (trialRoute, error) { calls.Add(1); return nil, errors.New("PRIVATE") }
	for _, raw := range []string{strings.TrimSuffix(configurationLine(1), "\n"), strings.Repeat("x", maximumLine+2), "private\n"} {
		out := newOutput()
		if code := run(context.Background(), strings.NewReader(raw), out, factory, ordinaryBounds()); code != 1 {
			t.Fatal("Malformed line did not fail")
		}
		closed := out.phase(t, "closed")
		if closed.Reason != "INPUT_REJECTED" || closed.Cleanup != "CONFIRMED" {
			t.Fatal("Malformed input gained resource uncertainty")
		}
	}
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	out := newOutput()
	limit := ordinaryBounds()
	limit.input = 20 * time.Millisecond
	if code := run(context.Background(), reader, out, factory, limit); code != 1 {
		t.Fatal("Blocked input did not time out")
	}
	if out.phase(t, "closed").Reason != "INPUT_TIMEOUT" || calls.Load() != 0 {
		t.Fatal("Input refusal started construction")
	}
}

func TestConstructionFailureOrPanicNeverReflectsPrivateErrors(t *testing.T) {
	for _, panics := range []bool{false, true} {
		trial := start(t, func(configuration) (trialRoute, error) {
			if panics {
				panic(privateFixture)
			}
			return nil, errors.New(privateFixture)
		}, ordinaryBounds(), 10)
		if panics {
			trial.ended(t, "STARTUP_FAILED", "UNCONFIRMED", 2)
		} else {
			trial.ended(t, "STARTUP_FAILED", "CONFIRMED", 1)
		}
	}
}

func TestBlockedConstructionTimesOutAndLateResultStillHasCleanupOwner(t *testing.T) {
	for _, reason := range []string{"STARTUP_TIMEOUT", "SIGNAL", "EOF"} {
		t.Run(reason, func(t *testing.T) {
			entered, release, closed := make(chan struct{}), make(chan struct{}), make(chan struct{})
			route := &fakeRoute{portNumber: 12345, close: func() error { close(closed); return nil }}
			limit := ordinaryBounds()
			if reason == "STARTUP_TIMEOUT" {
				limit.startup = 30 * time.Millisecond
			}
			limit.cleanup = 30 * time.Millisecond
			trial := start(t, func(configuration) (trialRoute, error) {
				close(entered)
				<-release
				return route, nil
			}, limit, 10)
			<-entered
			switch reason {
			case "SIGNAL":
				trial.cancel()
			case "EOF":
				trial.input.Close()
			}
			trial.ended(t, reason, "UNCONFIRMED", 2)
			close(release)
			select {
			case <-closed:
			case <-time.After(time.Second):
				t.Fatal("Late constructor result lost resource ownership")
			}
			if strings.Contains(trial.output.text(), `"phase":"ready"`) || route.calls.Load() != 1 {
				t.Fatal("Late construction published readiness or duplicated close")
			}
		})
	}
}

func TestCancellationDuringConstructionWaitsForActualCloseWithinSingleCleanupBudget(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	route := &fakeRoute{portNumber: 12345}
	trial := start(t, func(configuration) (trialRoute, error) { close(entered); <-release; return route, nil }, ordinaryBounds(), 10)
	<-entered
	trial.cancel()
	close(release)
	trial.ended(t, "SIGNAL", "CONFIRMED", 0)
	if route.calls.Load() != 1 {
		t.Fatal("Cancelled constructor was not closed")
	}
}

func TestFailedPanickingOrBlockedCloseNeverClaimsConfirmation(t *testing.T) {
	for _, mode := range []string{"error", "panic", "blocked"} {
		t.Run(mode, func(t *testing.T) {
			release, completed := make(chan struct{}), make(chan struct{})
			route := &fakeRoute{portNumber: 12345, close: func() error {
				defer close(completed)
				switch mode {
				case "panic":
					panic(privateFixture)
				case "blocked":
					<-release
					return nil
				default:
					return errors.New(privateFixture)
				}
			}}
			limit := ordinaryBounds()
			limit.cleanup = 25 * time.Millisecond
			trial := start(t, func(configuration) (trialRoute, error) { return route, nil }, limit, 10)
			trial.output.phase(t, "ready")
			io.WriteString(trial.input, "stop\n")
			trial.ended(t, "STOP", "UNCONFIRMED", 2)
			if mode == "blocked" {
				close(release)
			}
			<-completed
			if route.calls.Load() != 1 {
				t.Fatal("Cleanup was retried")
			}
		})
	}
}

func TestInvalidReadyPortAndReturnedErrorResourceAreClosed(t *testing.T) {
	for _, mode := range []string{"port", "error"} {
		route := &fakeRoute{portNumber: 0}
		trial := start(t, func(configuration) (trialRoute, error) {
			if mode == "error" {
				return route, errors.New(privateFixture)
			}
			return route, nil
		}, ordinaryBounds(), 10)
		trial.ended(t, "STARTUP_FAILED", "CONFIRMED", 1)
		if route.calls.Load() != 1 || strings.Contains(trial.output.text(), `"phase":"ready"`) {
			t.Fatal("Unready resource escaped cleanup")
		}
	}
}
