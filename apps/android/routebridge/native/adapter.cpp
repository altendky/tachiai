#include "adapter.h"
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <client/ovpncli.hpp>
#include <condition_variable>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <openvpn/common/stop.hpp>
#include <set>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
std::atomic<bool> hold_next_worker{false};
std::atomic<bool> held_worker_waiting{false};
#endif
bool safe_word(const std::string &word, size_t limit) {
  if (word.empty() || word.size() > limit)
    return false;
  for (const unsigned char c : word)
    if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') &&
        !(c >= '0' && c <= '9') && c != '.' && c != '-' && c != '_' && c != ':')
      return false;
  return true;
}
bool tls_failure(const std::string &name) {
  return name == "CERT_VERIFY_FAIL" || name == "SSL_ERROR";
}
bool numeric_remote(const std::string &host) {
  in_addr address4{};
  if (::inet_pton(AF_INET, host.c_str(), &address4) == 1) {
    const auto value = ntohl(address4.s_addr);
    return (value & 0xff000000u) != 0 && value != 0xffffffffu &&
           (value & 0xf0000000u) != 0xe0000000u;
  }
  in6_addr address6{};
  return ::inet_pton(AF_INET6, host.c_str(), &address6) == 1 &&
         !IN6_IS_ADDR_UNSPECIFIED(&address6) &&
         !IN6_IS_ADDR_MULTICAST(&address6) &&
         !IN6_IS_ADDR_V4MAPPED(&address6) &&
         !IN6_IS_ADDR_LINKLOCAL(&address6);
}

// Deliberately small, self-contained certificate subset. Core may silently
// ignore scripts/options, so application policy is checked before eval_config.
bool allowed_profile(const std::string &profile) {
  if (profile.empty() || profile.size() > 8192)
    return false;
  for (const unsigned char c : profile)
    if (c == 0 || (c < 32 && c != '\n' && c != '\r' && c != '\t'))
      return false;
  std::set<std::string> seen;
  std::string block;
  std::istringstream lines(profile);
  for (std::string line; std::getline(lines, line);) {
    const auto first = line.find_first_not_of(" \t\r");
    if (first == std::string::npos)
      continue;
    line = line.substr(first);
    const auto last = line.find_last_not_of(" \t\r");
    line.resize(last + 1);
    if (!block.empty()) {
      if (line == "</" + block + ">")
        block.clear();
      else if (line.find('<') != std::string::npos ||
               line.find("ENCRYPTED") != std::string::npos)
        return false;
      continue;
    }
    if (line[0] == '#' || line[0] == ';')
      continue;
    if (line == "<ca>" || line == "<cert>" || line == "<key>") {
      block = line.substr(1, line.size() - 2);
      if (!seen.insert(block).second)
        return false;
      continue;
    }
    std::istringstream words(line);
    std::vector<std::string> args;
    for (std::string word; words >> word;)
      args.push_back(word);
    if (args.empty() || !seen.insert(args[0]).second)
      return false;
    const auto &name = args[0];
    if (name == "client" || name == "nobind") {
      if (args.size() != 1)
        return false;
    } else if (name == "dev") {
      if (args.size() != 2 || args[1] != "tun")
        return false;
    } else if (name == "proto") {
      if (args.size() != 2 || (args[1] != "udp" && args[1] != "tcp-client"))
        return false;
    } else if (name == "remote") {
      if (args.size() != 3 || !numeric_remote(args[1]) || args[2].empty() ||
          args[2].size() > 5)
        return false;
      for (const char c : args[2])
        if (c < '0' || c > '9')
          return false;
      const int port = std::stoi(args[2]);
      if (port < 1 || port > 65535)
        return false;
    } else if (name == "remote-cert-tls") {
      if (args.size() != 2 || args[1] != "server")
        return false;
    } else if (name == "verify-x509-name") {
      if (args.size() != 3 || !safe_word(args[1], 253) || args[2] != "name")
        return false;
    } else if (name == "tls-version-min") {
      if (args.size() != 2 || (args[1] != "1.2" && args[1] != "1.3"))
        return false;
    } else
      return false;
  }
  return block.empty() && seen.contains("client") && seen.contains("dev") &&
         seen.contains("proto") && seen.contains("remote") &&
         seen.contains("remote-cert-tls") &&
         seen.contains("verify-x509-name") &&
         seen.contains("tls-version-min") && seen.contains("ca") &&
         seen.contains("cert") && seen.contains("key");
}
} // namespace

// Core keeps an AsioStopScope referencing get_async_stop() until its base
// destructor runs. A derived member is destroyed BEFORE that base destructor,
// so cancellation must be in an earlier base, which is destroyed LAST.
struct cancellation_owner {
  openvpn::Stop cancellation;
};

struct tachiai_ovpn final : cancellation_owner,
                            openvpn::ClientAPI::OpenVPNClient {
  std::mutex mutex;
  std::condition_variable changed;
  std::thread worker;
  bool worker_finished = false;
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
  bool test_hold_worker = false;
#endif
  tachiai_ovpn_spec spec{};
  int state = TACHIAI_OVPN_PREPARED;
  int failure = TACHIAI_OVPN_FAILURE_NONE;
  int peer = -1;
  bool taken = false;
  bool cancelled = false;

  ~tachiai_ovpn() override {
    // Started clients reach destruction only after the checked C ABI confirms
    // worker completion and joins it. An unfinished client is retained.
    cancel();
    close_peer();
  }
  void close_peer() {
    if (peer >= 0) {
      ::shutdown(peer, SHUT_RDWR);
      ::close(peer);
      peer = -1;
    }
  }
  void cancel() {
    {
      std::lock_guard lock(mutex);
      cancelled = true;
      close_peer();
      state = TACHIAI_OVPN_CLOSED;
      changed.notify_all();
    }
    cancellation.stop();
  }
  openvpn::Stop *get_async_stop() override { return &cancellation; }
  bool pause_on_connection_timeout() override { return false; }
  void log(const openvpn::ClientAPI::LogInfo &) override {}
  void
  acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {
  }
  void external_pki_cert_request(
      openvpn::ClientAPI::ExternalPKICertRequest &request) override {
    request.error = true;
  }
  void external_pki_sign_request(
      openvpn::ClientAPI::ExternalPKISignRequest &request) override {
    request.error = true;
  }
  void event(const openvpn::ClientAPI::Event &event) override {
    bool failed = false;
    {
      std::lock_guard lock(mutex);
      if (cancelled)
        return;
      if (tls_failure(event.name))
        failure = TACHIAI_OVPN_FAILURE_TLS;
      if (event.name == "CONNECTION_TIMEOUT")
        failure = TACHIAI_OVPN_FAILURE_TIMEOUT;
      if (event.name == "CONNECTED")
        state = TACHIAI_OVPN_CONNECTED;
      else if (event.fatal || event.name == "DISCONNECTED" ||
               event.name == "RECONNECTING" || event.name == "AUTH_PENDING") {
        if (failure == TACHIAI_OVPN_FAILURE_NONE)
          failure = TACHIAI_OVPN_FAILURE_CONNECTION;
        state = TACHIAI_OVPN_FAILED;
        close_peer();
        failed = true;
      }
      changed.notify_all();
    }
    if (failed)
      cancellation.stop();
  }
  bool tun_builder_new() override {
    std::lock_guard lock(mutex);
    close_peer();
    spec = {};
    taken = false;
    state = TACHIAI_OVPN_CONNECTING;
    return !cancelled;
  }
  bool tun_builder_set_layer(int layer) override { return layer == 3; }
  bool tun_builder_set_remote_address(const std::string &, bool) override {
    return true;
  }
  bool tun_builder_add_address(const std::string &address, int,
                               const std::string &, bool, bool) override {
    std::lock_guard lock(mutex);
    if (cancelled || spec.address_count == 16 || address.size() >= 46)
      return false;
    std::strcpy(spec.addresses[spec.address_count++], address.c_str());
    return true;
  }
  bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
    std::lock_guard lock(mutex);
    if (cancelled)
      return false;
    for (const auto &[priority, server] : dns.servers) {
      (void)priority;
      if (!server.domains.empty() || !server.sni.empty() ||
          (server.transport != openvpn::DnsServer::Transport::Unset &&
           server.transport != openvpn::DnsServer::Transport::Plain) ||
          server.dnssec == openvpn::DnsServer::Security::Yes)
        return false;
      for (const auto &address : server.addresses) {
        if ((address.port != 0 && address.port != 53) || spec.dns_count == 16 ||
            address.address.size() >= 46)
          return false;
        std::strcpy(spec.dns[spec.dns_count++], address.address.c_str());
      }
    }
    return true;
  }
  bool tun_builder_set_mtu(int mtu) override {
    std::lock_guard lock(mutex);
    if (cancelled || mtu < 576 || mtu > 9000)
      return false;
    spec.mtu = mtu;
    return true;
  }
  bool tun_builder_set_session_name(const std::string &) override {
    return true;
  }
  bool tun_builder_reroute_gw(bool, bool, unsigned int) override {
    return true;
  }
  bool tun_builder_add_route(const std::string &, int, int, bool) override {
    return true;
  }
  bool tun_builder_exclude_route(const std::string &, int, int, bool) override {
    return false;
  }
  bool tun_builder_persist() override { return false; }
  int tun_builder_establish() override {
    std::lock_guard lock(mutex);
    if (cancelled || spec.address_count == 0 || spec.dns_count == 0 ||
        spec.mtu == 0)
      return -1;
    int sockets[2];
    if (::socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, sockets) != 0)
      return -1;
    peer = sockets[1];
    return sockets[0]; // Core owns sockets[0].
  }
  void tun_builder_teardown(bool) override {
    std::lock_guard lock(mutex);
    close_peer();
    changed.notify_all();
  }
};

extern "C" tachiai_ovpn *tachiai_ovpn_create(const char *profile, size_t length,
                                             int timeout) {
  if (!profile || length == 0 || length > 8192 || timeout < 1 || timeout > 30)
    return nullptr;
  try {
    const std::string content(profile, length);
    if (!allowed_profile(content))
      return nullptr;
    auto client = std::make_unique<tachiai_ovpn>();
    openvpn::ClientAPI::Config config;
    config.content = content;
    config.connTimeout = timeout;
    config.tunPersist = false;
    config.googleDnsFallback = false;
    config.allowUnusedAddrFamilies = "no";
    config.compressionMode = "no";
    config.enableLegacyAlgorithms = false;
    config.enableNonPreferredDCAlgorithms = false;
    config.autologinSessions = false;
    const auto evaluated = client->eval_config(config);
    if (evaluated.error || !evaluated.autologin || evaluated.externalPki ||
        evaluated.privateKeyPasswordRequired ||
        !evaluated.staticChallenge.empty())
      return nullptr;
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
    client->test_hold_worker = hold_next_worker.exchange(false);
#endif
    return client.release();
  } catch (...) {
    return nullptr;
  }
}
extern "C" int tachiai_ovpn_start(tachiai_ovpn *client) {
  if (!client)
    return TACHIAI_OVPN_INVALID;
  std::lock_guard lock(client->mutex);
  if (client->state != TACHIAI_OVPN_PREPARED)
    return client->state;
  client->state = TACHIAI_OVPN_CONNECTING;
  try {
    client->worker = std::thread([client] {
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
      {
        std::unique_lock lock(client->mutex);
        if (client->test_hold_worker)
          held_worker_waiting.store(true);
        client->changed.wait(lock, [client] { return !client->test_hold_worker; });
        held_worker_waiting.store(false);
      }
#endif
      openvpn::ClientAPI::Status result;
      try {
        result = client->connect();
      } catch (...) {
      }
      std::lock_guard lock(client->mutex);
      if (tls_failure(result.status))
        client->failure = TACHIAI_OVPN_FAILURE_TLS;
      if (result.status == "CONNECTION_TIMEOUT")
        client->failure = TACHIAI_OVPN_FAILURE_TIMEOUT;
      if (!client->cancelled && client->failure == TACHIAI_OVPN_FAILURE_NONE)
        client->failure = TACHIAI_OVPN_FAILURE_CONNECTION;
      client->state =
          client->cancelled ? TACHIAI_OVPN_CLOSED : TACHIAI_OVPN_FAILED;
      client->close_peer();
      client->worker_finished = true;
      client->changed.notify_all();
    });
  } catch (...) {
    client->state = TACHIAI_OVPN_FAILED;
  }
  return client->state;
}
extern "C" int tachiai_ovpn_wait(tachiai_ovpn *client, int milliseconds) {
  if (!client || milliseconds < 0 || milliseconds > 30000)
    return TACHIAI_OVPN_INVALID;
  std::unique_lock lock(client->mutex);
  client->changed.wait_for(
      lock, std::chrono::milliseconds(milliseconds),
      [client] { return client->state != TACHIAI_OVPN_CONNECTING; });
  return client->state;
}
extern "C" int tachiai_ovpn_wait_change(tachiai_ovpn *client, int previous,
                                        int milliseconds) {
  if (!client || previous < TACHIAI_OVPN_PREPARED ||
      previous > TACHIAI_OVPN_CLOSED || milliseconds < 0 ||
      milliseconds > 30000)
    return TACHIAI_OVPN_INVALID;
  std::unique_lock lock(client->mutex);
  client->changed.wait_for(
      lock, std::chrono::milliseconds(milliseconds),
      [client, previous] { return client->state != previous; });
  return client->state;
}
extern "C" int tachiai_ovpn_failure_reason(tachiai_ovpn *client) {
  if (!client)
    return TACHIAI_OVPN_FAILURE_CONNECTION;
  std::lock_guard lock(client->mutex);
  return client->failure;
}
extern "C" int tachiai_ovpn_take_packet_fd(tachiai_ovpn *client,
                                           tachiai_ovpn_spec *spec) {
  if (!client || !spec)
    return -1;
  std::lock_guard lock(client->mutex);
  if (client->state != TACHIAI_OVPN_CONNECTED || client->taken ||
      client->peer < 0)
    return -1;
  const int fd = ::fcntl(client->peer, F_DUPFD_CLOEXEC, 0);
  if (fd >= 0) {
    *spec = client->spec;
    client->taken = true;
  }
  return fd;
}
extern "C" void tachiai_ovpn_cancel(tachiai_ovpn *client) {
  if (client)
    client->cancel();
}
extern "C" int tachiai_ovpn_destroy(tachiai_ovpn *client) {
  if (!client)
    return 1;
  client->cancel();
  {
    std::unique_lock lock(client->mutex);
    if (!client->changed.wait_for(lock, std::chrono::seconds(5), [client] {
          return !client->worker.joinable() || client->worker_finished;
        }))
      return 0; // Retain live worker and its entire opaque client state.
  }
  try {
    if (client->worker.joinable())
      client->worker.join();
  } catch (...) {
    return 0;
  }
  delete client;
  return 1;
}
#ifdef TACHIAI_OPENVPN_TEST_HOOKS
extern "C" void tachiai_ovpn_test_hold_worker(tachiai_ovpn *client, int held) {
  std::lock_guard lock(client->mutex);
  client->test_hold_worker = held != 0;
  client->changed.notify_all();
}
extern "C" void tachiai_ovpn_test_hold_next_worker() {
  held_worker_waiting.store(false);
  hold_next_worker.store(true);
}
extern "C" int tachiai_ovpn_test_worker_waiting() {
  return held_worker_waiting.load() ? 1 : 0;
}
#endif
