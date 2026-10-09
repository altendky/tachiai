#include "adapter.h"

// Cross-compiled shared-link fixture, never packaged or executed by the app.
extern "C" int tachiai_openvpn_linkcheck(const char *profile, unsigned length) {
  auto *client = tachiai_ovpn_create(profile, length, 1);
  if (!client)
    return TACHIAI_OVPN_INVALID;
  const auto state = tachiai_ovpn_start(client);
  tachiai_ovpn_cancel(client);
  tachiai_ovpn_destroy(client);
  return state;
}
