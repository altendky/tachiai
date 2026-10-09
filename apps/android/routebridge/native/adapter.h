#pragma once
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Experimental C ABI: not registered as an application route backend.
typedef struct tachiai_ovpn tachiai_ovpn;
enum tachiai_ovpn_status {
  TACHIAI_OVPN_INVALID = 0,
  TACHIAI_OVPN_PREPARED = 1,
  TACHIAI_OVPN_CONNECTING = 2,
  TACHIAI_OVPN_CONNECTED = 3,
  TACHIAI_OVPN_FAILED = 4,
  TACHIAI_OVPN_CLOSED = 5
};
enum tachiai_ovpn_failure {
  TACHIAI_OVPN_FAILURE_NONE = 0,
  TACHIAI_OVPN_FAILURE_TLS = 1,
  TACHIAI_OVPN_FAILURE_CONNECTION = 2,
  TACHIAI_OVPN_FAILURE_TIMEOUT = 3
};

typedef struct {
  int mtu;
  int address_count;
  char addresses[16][46];
  int dns_count;
  char dns[16][46];
} tachiai_ovpn_spec;

// Only fixed status codes cross this boundary. Profile/error/log text is never
// returned. create copies at most 8 KiB and consumes no files or credentials.
tachiai_ovpn *tachiai_ovpn_create(const char *profile, size_t length,
                                  int timeout_seconds);
int tachiai_ovpn_start(tachiai_ovpn *);
int tachiai_ovpn_wait(tachiai_ovpn *, int milliseconds);
int tachiai_ovpn_wait_change(tachiai_ovpn *, int previous_status,
                             int milliseconds);
int tachiai_ovpn_failure_reason(tachiai_ovpn *);
// On success, caller owns a duplicate datagram FD. Core owns the opposite end.
// Datagram sockets have no reliable stream EOF: owner must close its packet
// bridge on FAILED/CLOSED status, rather than wait for native FD closure.
int tachiai_ovpn_take_packet_fd(tachiai_ovpn *, tachiai_ovpn_spec *);
// cancel is idempotent, thread safe, and valid before start or during connect.
void tachiai_ovpn_cancel(tachiai_ovpn *);
// Owner excludes other callers. 1 confirms worker completion and release.
// 0 means the worker failed to finish within five seconds: handle is retained,
// never freed or detached, and application cleanup must fail closed.
int tachiai_ovpn_destroy(tachiai_ovpn *);
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
// Host fixture only; omitted from every Android build.
void tachiai_ovpn_test_hold_worker(tachiai_ovpn *, int held);
void tachiai_ovpn_test_hold_next_worker(void);
int tachiai_ovpn_test_worker_waiting(void);
#endif

#ifdef __cplusplus
}
#endif
