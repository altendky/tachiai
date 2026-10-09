//go:build openvpn_validation && cgo && !android

package routebridge

/*
#define TACHIAI_OPENVPN_TEST_HOOKS
#include "adapter.h"
*/
import "C"

// Owned host fixtures only. Android has neither these Go helpers nor C hooks.
func holdNextOpenVPNFixtureWorker()     { C.tachiai_ovpn_test_hold_next_worker() }
func openVPNFixtureWorkerWaiting() bool { return C.tachiai_ovpn_test_worker_waiting() == 1 }
func releaseRetainedOpenVPNFixtureWorkers() bool {
	retainedOpenVPNMutex.Lock()
	clients := retainedOpenVPNClients
	retainedOpenVPNClients = nil
	retainedOpenVPNMutex.Unlock()
	for _, client := range clients {
		C.tachiai_ovpn_test_hold_worker(client, 0)
		if C.tachiai_ovpn_destroy(client) != 1 {
			return false
		}
	}
	openVPNUnconfirmed.Store(false)
	return true
}
