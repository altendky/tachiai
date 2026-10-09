#include "adapter.h"
#include <chrono>
#include <dirent.h>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
void require(bool success, const char *message) {
  if (!success)
    throw std::runtime_error(message);
}
std::string read(const char *path) {
  std::ifstream input(path);
  require(bool(input), "Fixture input missing");
  return std::string(std::istreambuf_iterator<char>(input), {});
}
tachiai_ovpn *create(const std::string &profile, int timeout = 2) {
  return tachiai_ovpn_create(profile.data(), profile.size(), timeout);
}
int descriptor_count() {
  DIR *directory = opendir("/proc/self/fd");
  require(directory, "Descriptor fixture unavailable");
  int count = 0;
  while (const auto *entry = readdir(directory))
    if (entry->d_name[0] != '.')
      ++count;
  closedir(directory);
  return count;
}
} // namespace
int main(int argc, char **argv) {
  try {
    require(argc == 3, "Expected fixture profile and mode");
    const auto profile = read(argv[1]);
    const std::string mode = argv[2];
    if (mode == "policy") {
      require(profile.size() <= 8192,
              "Synthetic profile exceeds existing bound");
      for (const auto &extra :
           {"up command\n", "auth-user-pass\n", "management 127.0.0.1 9999\n",
            "ca /tmp/file\n", "<key>\nextra\n</key>\n",
            "remote 127.0.0.1 443\n", "compress lz4\n"}) {
        require(create(profile + extra) == nullptr,
                "Unsupported profile option accepted");
      }
      require(create(std::string(8193, 'x')) == nullptr,
              "Profile size bound bypassed");
      require(tachiai_ovpn_create(profile.data(), profile.size(), 0) == nullptr,
              "Timeout bound bypassed");
      const auto remote = profile.find("remote 127.0.0.1 ");
      require(remote != std::string::npos, "Numeric remote fixture missing");
      for (const auto *address : {"hostname.owned-route.test", "0.0.0.0", "224.0.0.1",
                                 "255.255.255.255", "::", "ff02::1", "fe80::1",
                                 "fe80::1%eth0", "::ffff:127.0.0.1"}) {
        auto invalid = profile;
        invalid.replace(remote + 7, 9, address);
        require(create(invalid) == nullptr, "Unsupported bootstrap address accepted");
      }
      auto ipv6 = profile;
      ipv6.replace(remote + 7, 9, "::1");
      auto *ipv6_client = create(ipv6);
      require(ipv6_client, "Numeric IPv6 profile refused");
      tachiai_ovpn_destroy(ipv6_client);
      auto *client = create(profile);
      require(client, "Self-contained certificate profile refused");
      tachiai_ovpn_cancel(client);
      require(tachiai_ovpn_start(client) == TACHIAI_OVPN_CLOSED,
              "Cancel before start lost");
      tachiai_ovpn_destroy(client);
      for (int repetition = 0; repetition < 20; repetition++) {
        client = create(profile);
        require(client, "Repeated profile creation refused");
        require(tachiai_ovpn_start(client) == TACHIAI_OVPN_CONNECTING,
                "Worker failed to start");
        std::vector<std::thread> callers;
        for (int n = 0; n < 4; n++)
          callers.emplace_back([client] { tachiai_ovpn_cancel(client); });
        for (auto &caller : callers)
          caller.join();
        require(tachiai_ovpn_wait(client, 100) == TACHIAI_OVPN_CLOSED,
                "Concurrent cancellation lost");
        tachiai_ovpn_destroy(client);
      }
      client = create(profile, 1);
      require(client, "Timeout profile creation refused");
      const auto started = std::chrono::steady_clock::now();
      tachiai_ovpn_start(client);
      require(tachiai_ovpn_wait(client, 5000) == TACHIAI_OVPN_FAILED,
              "Connection timeout did not fail");
      require(tachiai_ovpn_failure_reason(client) == TACHIAI_OVPN_FAILURE_TIMEOUT,
              "Connection timeout lost its fixed failure category");
      tachiai_ovpn_destroy(client);
      require(std::chrono::steady_clock::now() - started >
                  std::chrono::milliseconds(750),
              "Timeout fixture failed before exercising its deadline");
      require(std::chrono::steady_clock::now() - started <
                  std::chrono::seconds(4),
              "Connection timeout exceeded bound");
      client = create(profile);
      require(client, "Withheld-worker fixture refused");
      tachiai_ovpn_test_hold_worker(client, 1);
      tachiai_ovpn_start(client);
      const auto cleanup_started = std::chrono::steady_clock::now();
      require(tachiai_ovpn_destroy(client) == 0,
              "Unfinished native worker reported confirmed cleanup");
      const auto elapsed = std::chrono::steady_clock::now() - cleanup_started;
      require(elapsed > std::chrono::milliseconds(4500) && elapsed < std::chrono::seconds(6),
              "Unfinished worker cleanup did not respect five-second bound");
      // Only this deterministic owned fixture releases the retained worker;
      // production must keep cleanup sticky and require process shutdown.
      tachiai_ovpn_test_hold_worker(client, 0);
      require(tachiai_ovpn_destroy(client) == 1,
              "Released retained worker could not be cleaned by fixture owner");
    } else {
      auto *warmup = create(profile);
      require(warmup, "TLS fixture warmup refused");
      tachiai_ovpn_destroy(warmup);
      const int before = descriptor_count();
      auto *client = create(profile, mode == "pending" ? 30 : 2);
      require(client, "TLS fixture profile refused");
      tachiai_ovpn_start(client);
      const auto started = std::chrono::steady_clock::now();
      const auto status = tachiai_ovpn_wait(client, 5000);
      if (mode == "connected") {
        require(status == TACHIAI_OVPN_CONNECTED,
                "Owned TLS connection failed");
        tachiai_ovpn_spec spec{};
        const int fd = tachiai_ovpn_take_packet_fd(client, &spec);
        require(fd >= 0 && spec.address_count == 1 && spec.dns_count == 1 &&
                    spec.mtu >= 576,
                "Tunnel metadata or owned FD missing");
        int kind = 0;
        socklen_t length = sizeof(kind);
        require(getsockopt(fd, SOL_SOCKET, SO_TYPE, &kind, &length) == 0 &&
                    kind == SOCK_DGRAM,
                "Native packet FD framing wrong");
        require(tachiai_ovpn_take_packet_fd(client, &spec) == -1,
                "Packet FD transferred twice");
        tachiai_ovpn_cancel(client);
        // Datagram teardown is explicit: Go bridge owner closes its
        // connection to interrupt readers, not an assumed stream EOF.
        require(tachiai_ovpn_wait(client, 100) == TACHIAI_OVPN_CLOSED,
                "Native cancellation lost");
        require(close(fd) == 0, "Owned packet FD close failed");
      } else if (mode == "pending") {
        require(status == TACHIAI_OVPN_FAILED &&
                    tachiai_ovpn_failure_reason(client) == TACHIAI_OVPN_FAILURE_CONNECTION,
                "Server pending authentication was not refused");
        require(std::chrono::steady_clock::now() - started < std::chrono::seconds(2),
                "Server pending authentication extended preparation timeout");
      } else {
        require(status == TACHIAI_OVPN_FAILED,
                "Invalid TLS peer accepted or timeout unbounded");
        require(tachiai_ovpn_failure_reason(client) == TACHIAI_OVPN_FAILURE_TLS,
                "TLS refusal fixture only timed out");
      }
      tachiai_ovpn_destroy(client);
      require(descriptor_count() == before,
              "Native lifecycle leaked descriptors");
    }
    std::cout << "Owned native " << mode << " fixture passed\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
