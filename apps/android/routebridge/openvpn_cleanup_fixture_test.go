//go:build openvpn_validation && cgo && !android

package routebridge

import (
	"os"
	"testing"
	"time"
)

func TestOpenVPNNativeUnconfirmedCleanup(t *testing.T) {
	path := os.Getenv("TACHIAI_OPENVPN_FIXTURE_PROFILE")
	if path == "" {
		t.Skip("Requires generated owned native fixture")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("Owned profile unavailable")
	}
	holdNextOpenVPNFixtureWorker()
	preparation := NewRoutePreparation()
	finished := make(chan *OpenVPNPreparedResult, 1)
	go func() {
		finished <- NewOpenVPNPrepared(string(content), localUser, localPassword, localRealm, "owned-route.test", preparation)
	}()
	deadline := time.Now().Add(time.Second)
	for !openVPNFixtureWorkerWaiting() {
		if time.Now().After(deadline) {
			t.Fatal("Owned native worker was not withheld")
		}
		time.Sleep(time.Millisecond)
	}
	started := time.Now()
	preparation.Cancel()
	if time.Since(started) > 50*time.Millisecond {
		t.Fatal("UI cancellation waited for held native worker")
	}
	select {
	case result := <-finished:
		if result.GetCode() != 6 || result.GetRoute() != nil || time.Since(started) < 4500*time.Millisecond {
			t.Fatal("Unfinished worker cleanup was reported as confirmed")
		}
	case <-time.After(6 * time.Second):
		t.Fatal("Unfinished native cleanup exceeded bounded wait")
	}
	blocked := NewOpenVPNPrepared(string(content), localUser, localPassword, localRealm, "owned-route.test", NewRoutePreparation())
	if blocked.GetCode() != 6 || blocked.GetRoute() != nil {
		t.Fatal("Unconfirmed cleanup allowed another OpenVPN engine")
	}
	// Production never retries/free-detaches this worker. Only the owned host
	// fixture releases its injected gate after failure so test resources can exit.
	if !releaseRetainedOpenVPNFixtureWorkers() {
		t.Fatal("Owned retained worker could not be released by fixture")
	}
}
