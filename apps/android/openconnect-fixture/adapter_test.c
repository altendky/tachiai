/* Reuse only the owned CA/server and strict assertion helpers, retaining the
 * original lifecycle executable as a separate regression proof. */
#define main lifecycle_fixture_main
#include "lifecycle.c"
#undef main
#include "adapter.h"
#include "memory_file.h"
#include <stdbool.h>
#include <limits.h>
#include <linux/memfd.h>

static struct toc_bytes bytes(const gnutls_datum_t *d) { return (struct toc_bytes){d->data, d->size}; }
static struct toc_profile profile(struct identity *ca, struct identity *client, const char *url) {
    return (struct toc_profile){.endpoint = {url, strlen(url)}, .ca_pem = bytes(&ca->pem),
        .client_pem = bytes(&client->pem), .key_pem = bytes(&client->secret), .bootstrap_ipv4 = "127.0.0.1"};
}
static void adapter_run(enum mode mode, struct identity *ca, struct identity *wrong_ca,
    struct identity *client, struct identity *gateway, bool cancel_before_start, bool check_inventory) {
    int before = fd_count();
    struct fixture f = {.mode = mode};
    f.listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0); require(f.listener >= 0, "adapter owned listener");
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    require(bind(f.listener, (struct sockaddr *)&address, sizeof(address)) == 0 && listen(f.listener, 2) == 0, "adapter listener bind");
    socklen_t size = sizeof(address); require(getsockname(f.listener, (struct sockaddr *)&address, &size) == 0, "adapter owned port");
    f.port = ntohs(address.sin_port);
    unsigned char payload[sizeof(packet)]; memcpy(payload, packet, sizeof(packet));
    payload[4] = (unsigned char)(f.port >> 8); payload[5] = (unsigned char)f.port;
    f.payload = payload;
    require(pipe2(f.seen, O_CLOEXEC) == 0, "adapter server readiness");
    crypto(gnutls_certificate_allocate_credentials(&f.credentials));
    crypto(gnutls_certificate_set_x509_key_mem(f.credentials, &gateway->pem, &gateway->secret, GNUTLS_X509_FMT_PEM));
    crypto(gnutls_certificate_set_x509_trust_mem(f.credentials, &ca->pem, GNUTLS_X509_FMT_PEM));
    char url[96]; snprintf(url, sizeof(url), "https://%s:%d/", mode == WRONG_HOST ? "wrong.invalid" : "fixture.invalid", f.port);
    struct toc_profile p = profile(mode == WRONG_CA ? wrong_ca : ca, client, url);
    struct toc_session *session = NULL;
    require(toc_create(&p, &session) == TOC_OK, "bounded adapter construction before connect");
    int peer = -1, second = -1;
    require(toc_take_packet_fd(session, &peer) == TOC_OK && peer >= 0 &&
        toc_take_packet_fd(session, &second) == TOC_BUSY && second == -1, "packet FD transfers once");
    require(fcntl(peer, F_GETFD) & FD_CLOEXEC, "transferred peer CLOEXEC");
    pthread_t server_thread;
    if (cancel_before_start) toc_cancel(session);
    else require(pthread_create(&server_thread, NULL, server, &f) == 0, "adapter server start");
    require(toc_start(session) == TOC_OK && toc_start(session) == TOC_BUSY, "one adapter worker");
    if (!cancel_before_start && (mode == CANCEL_AUTH || mode == CANCEL_TLS)) {
        wait_readable(f.seen[0]);
        require(toc_destroy(session) == TOC_BUSY, "active native worker cannot be freed");
        toc_cancel(session);
    }
    if (!cancel_before_start && (mode == TRUNCATED_LENGTH || mode == TRUNCATED_CHUNK)) {
        wait_readable(f.seen[0]); char progress[2] = {0};
        require(read(f.seen[0], progress, sizeof(progress)) >= 1, "truncated response started");
        if (progress[0] != 'e' && (progress[0] != 'a' || progress[1] != 'e')) wait_readable(f.seen[0]);
        struct timespec settle = {.tv_nsec = 100000000}; nanosleep(&settle, NULL);
        toc_cancel(session); // Must remain bounded even after clean early TLS EOF.
    }
    if (!cancel_before_start && mode == PACKETS) {
        struct toc_snapshot info;
        require(toc_wait(session, 5000, &info) == TOC_READY && info.mtu == 1280 && info.dns_count == 1, "fixed negotiated adapter snapshot");
        struct in_addr expected; require(inet_pton(AF_INET, "10.2.0.2", &expected) == 1 && info.ipv4 == expected.s_addr, "adapter IPv4 snapshot");
        require(send(peer, payload, sizeof(payload), 0) == sizeof(payload), "adapter packet send");
        wait_readable(peer); unsigned char received[128];
        require(recv(peer, received, sizeof(received), 0) == sizeof(payload) && !memcmp(received, payload, sizeof(payload)), "adapter CSTP datagram roundtrip");
        toc_cancel(session);
    }
    require(toc_join(session, 5000) == TOC_OK && toc_join(session, 0) == TOC_OK, "worker joined before native owner destruction");
    require(toc_wait(session, 0, NULL) == (cancel_before_start || mode == PACKETS || mode == CANCEL_AUTH || mode == CANCEL_TLS ? TOC_CANCELLED : TOC_FAILED), "fixed failure/cancel metadata");
    toc_cancel(session); // Safe after worker has freed the command pipe.
    require(toc_destroy(session) == TOC_OK && fcntl(peer, F_GETFD) >= 0, "caller peer survives adapter destruction");
    close(peer);
    if (!cancel_before_start) join(server_thread, "adapter owned server join");
    else {
        struct pollfd listener = {.fd = f.listener, .events = POLLIN};
        require(poll(&listener, 1, 0) == 0, "pre-start cancellation opens no transport");
    }
    close(f.listener); close(f.seen[0]); close(f.seen[1]); gnutls_certificate_free_credentials(f.credentials);
    if (check_inventory) require(fd_count() == before, "adapter lifecycle has no FD leak");
}
struct isolation { struct identity ca, client, gateway; };
static void *isolated_run(void *argument) {
    struct isolation *i = argument;
    adapter_run(PACKETS, &i->ca, &i->ca, &i->client, &i->gateway, false, false);
    return NULL;
}
static void concurrent_isolation(void) {
    int before = fd_count();
    struct isolation sessions[2];
    pthread_t threads[2];
    for (unsigned n = 0; n < 2; n++) {
        sessions[n].ca = identity(NULL, n + 8, NULL);
        sessions[n].client = identity(&sessions[n].ca, n + 10, GNUTLS_KP_TLS_WWW_CLIENT);
        sessions[n].gateway = identity(&sessions[n].ca, n + 12, GNUTLS_KP_TLS_WWW_SERVER);
    }
    for (unsigned n = 0; n < 2; n++) require(pthread_create(&threads[n], NULL, isolated_run, &sessions[n]) == 0, "independent route start");
    for (unsigned n = 0; n < 2; n++) {
        join(threads[n], "concurrent isolated route join");
        free_identity(&sessions[n].gateway); free_identity(&sessions[n].client); free_identity(&sessions[n].ca);
    }
    require(fd_count() == before, "concurrent independent authorities/credentials/packets have no FD leak");
}
static void invalid_profiles(struct identity *ca, struct identity *client, struct identity *wrong) {
    struct toc_profile p = profile(ca, client, "https://fixture.invalid/");
    struct toc_session *s;
    int before = fd_count();
    p.key_pem = bytes(&wrong->secret);
    require(toc_create(&p, &s) == TOC_INVALID && !s, "mismatched key rejected before connect");
    p = profile(ca, client, "https://fixture.invalid/?secret=unsupported");
    require(toc_create(&p, &s) == TOC_INVALID && !s, "query endpoint rejected");
    p = profile(ca, client, "https://user:secret@fixture.invalid/");
    require(toc_create(&p, &s) == TOC_INVALID && !s, "endpoint credentials rejected");
    p = profile(ca, client, "https://fixture.invalid/");
    p.ca_pem.size = 8193; // Bounds must reject BEFORE reading the oversized field.
    require(toc_create(&p, &s) == TOC_INVALID && !s, "8KiB bound precedes access");
    p = profile(ca, client, "https://fixture.invalid/"); p.bootstrap_ipv4 = "fixture.invalid";
    require(toc_create(&p, &s) == TOC_INVALID && !s, "no implicit DNS bootstrap");
    p = profile(ca, client, "https://fixture.invalid/"); p.ca_pem = (struct toc_bytes){NULL, 0};
    require(toc_create(&p, &s) == TOC_INVALID && !s, "CA required");
    require(fd_count() == before, "invalid profile preserves descriptor inventory");
    char path[64]; int fd = toc_memory_file(client->secret.data, client->secret.size, path);
    require(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC), "syscall anonymous regular FD");
    require(write(fd, "x", 1) == -1 && errno == EPERM && ftruncate(fd, 0) == -1 && errno == EPERM, "credential FD cannot mutate after sealing");
    unsigned char copy[8192];
    off_t offset = lseek(fd, 0, SEEK_CUR);
    require(offset == (off_t)client->secret.size && !strncmp(path, "tachiai-sealed-fd:", 18), "fixed internal FD reference");
    require(pread(fd, copy, client->secret.size, 0) == (ssize_t)client->secret.size &&
        !memcmp(copy, client->secret.data, client->secret.size) && lseek(fd, 0, SEEK_CUR) == offset,
        "sealed credential direct read preserves offset without reopening procfs");
    explicit_bzero(copy, sizeof(copy));
    require(toc_memory_file(client->secret.data, 0, path) == -1 && errno == EINVAL &&
        toc_memory_file(client->secret.data, 8193, path) == -1 && errno == EINVAL &&
        toc_memory_file(NULL, 1, path) == -1 && errno == EINVAL, "memory FD bounds precede data access");
    close(fd); require(fd_count() == before, "anonymous memory FD cleanup");
}
/* Exercise the actual library reader through public configuration and an owned
 * TLS peer. Its refusal server requires no authentication request is sent. */
static void refused_reference(struct identity *ca, struct identity *gateway,
    const char *ca_ref, const char *cert_ref, const char *key_ref) {
    int before = fd_count();
    struct fixture f = {.mode = WRONG_CA}; atomic_init(&f.rejects, 0);
    f.listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    require(f.listener >= 0, "reference fixture listener");
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    require(bind(f.listener, (struct sockaddr *)&address, sizeof(address)) == 0 && listen(f.listener, 1) == 0, "reference fixture loopback bind");
    socklen_t length = sizeof(address);
    require(getsockname(f.listener, (struct sockaddr *)&address, &length) == 0, "reference fixture port");
    f.port = ntohs(address.sin_port);
    crypto(gnutls_certificate_allocate_credentials(&f.credentials));
    crypto(gnutls_certificate_set_x509_key_mem(f.credentials, &gateway->pem, &gateway->secret, GNUTLS_X509_FMT_PEM));
    crypto(gnutls_certificate_set_x509_trust_mem(f.credentials, &ca->pem, GNUTLS_X509_FMT_PEM));
    f.vpn = openconnect_vpninfo_new("tachiai-owned-reference", reject_cert, NULL, NULL, quiet, &f.rejects);
    require(f.vpn != NULL && openconnect_setup_cmd_pipe(f.vpn) >= 0, "reference fixture construction");
    require(openconnect_set_cafile(f.vpn, ca_ref) == 0 &&
        openconnect_set_client_cert(f.vpn, cert_ref, key_ref) == 0 &&
        openconnect_set_http_auth(f.vpn, "") == 0, "reference public configuration");
    openconnect_set_system_trust(f.vpn, 0);
    openconnect_override_getaddrinfo(f.vpn, resolve);
    char url[96]; snprintf(url, sizeof(url), "https://fixture.invalid:%d/", f.port);
    require(openconnect_parse_url(f.vpn, url) == 0, "reference fixture endpoint");
    pthread_t server_thread;
    require(pthread_create(&server_thread, NULL, server, &f) == 0, "reference refusal server");
    require(openconnect_obtain_cookie(f.vpn) < 0, "bad credential reference fails closed");
    openconnect_vpninfo_free(f.vpn);
    join(server_thread, "reference refusal bounded join");
    close(f.listener); gnutls_certificate_free_credentials(f.credentials);
    require(fd_count() == before, "reader borrows caller FDs without leaking library resources");
}
static void sealed_reference_tests(struct identity *ca, struct identity *client, struct identity *gateway) {
    int before = fd_count();
    char references[3][64];
    int owned[] = {toc_memory_file(ca->pem.data, ca->pem.size, references[0]),
        toc_memory_file(client->pem.data, client->pem.size, references[1]),
        toc_memory_file(client->secret.data, client->secret.size, references[2])};
    for (unsigned i = 0; i < 3; i++) require(owned[i] >= 0, "owned reference material");
    const char *malformed[] = {NULL, "", "tachiai-sealed-fd:", "tachiai-sealed-fd:-1",
        "tachiai-sealed-fd:+1", "tachiai-sealed-fd:01", "tachiai-sealed-fd:1tail",
        "tachiai-sealed-fd:2147483648", "tachiai-sealed-fd:999999999999999999999999",
        "tachiai-sealed-fd:2147483647", "/proc/self/fd/0", "keystore:owned", "/unavailable/owned-ca.pem"};
    for (unsigned i = 0; i < sizeof(malformed) / sizeof(malformed[0]); i++)
        refused_reference(ca, gateway, malformed[i], references[1], references[2]);
    char malformed_owned[80];
    for (unsigned i = 0; i < 3; i++) {
        snprintf(malformed_owned, sizeof(malformed_owned), "tachiai-sealed-fd:%dtail", owned[i]);
        refused_reference(ca, gateway, i == 0 ? malformed_owned : references[0],
            i == 1 ? malformed_owned : references[1], i == 2 ? malformed_owned : references[2]);
    }
    snprintf(malformed_owned, sizeof(malformed_owned), "tachiai-sealed-fd:+%d", owned[0]);
    refused_reference(ca, gateway, malformed_owned, references[1], references[2]);
    snprintf(malformed_owned, sizeof(malformed_owned), "tachiai-sealed-fd:0%d", owned[0]);
    refused_reference(ca, gateway, malformed_owned, references[1], references[2]);
    char invalid_ca[64];
    int invalid_ca_fd = toc_memory_file("synthetic non-certificate", 25, invalid_ca);
    require(invalid_ca_fd >= 0, "sealed invalid CA fixture");
    refused_reference(ca, gateway, invalid_ca, references[1], references[2]);
    close(invalid_ca_fd);
    int unsealed = memfd_create("tachiai-owned-unsealed", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    int empty = memfd_create("tachiai-owned-empty", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    int oversized = memfd_create("tachiai-owned-oversized", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    int pipefd[2];
    require(unsealed >= 0 && empty >= 0 && oversized >= 0 && pipe2(pipefd, O_CLOEXEC) == 0, "negative owned descriptor fixtures");
    require(write(unsealed, ca->pem.data, ca->pem.size) == (ssize_t)ca->pem.size &&
        ftruncate(oversized, 8193) == 0, "negative reference contents");
    int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    require(fcntl(empty, F_ADD_SEALS, seals) == 0 && fcntl(oversized, F_ADD_SEALS, seals) == 0, "negative reference seals");
    int refused[] = {unsealed, empty, oversized, pipefd[0]};
    for (unsigned i = 0; i < sizeof(refused) / sizeof(refused[0]); i++) {
        char reference[64]; snprintf(reference, sizeof(reference), "tachiai-sealed-fd:%d", refused[i]);
        refused_reference(ca, gateway, reference, references[1], references[2]);
    }
    require(fcntl(unsealed, F_ADD_SEALS, F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) == 0, "partially sealed negative fixture");
    char partial[64]; snprintf(partial, sizeof(partial), "tachiai-sealed-fd:%d", unsealed);
    refused_reference(ca, gateway, partial, references[1], references[2]);
    for (unsigned i = 0; i < 3; i++) {
        require(fcntl(owned[i], F_GETFD) >= 0, "caller credential FD survives library cleanup");
        close(owned[i]);
    }
    close(unsealed); close(empty); close(oversized); close(pipefd[0]); close(pipefd[1]);
    require(fd_count() == before, "sealed-reference negative fixture cleanup");
}
int main(void) {
    signal(SIGPIPE, SIG_IGN); crypto(openconnect_init_ssl());
    struct identity ca = identity(NULL, 1, NULL), wrong_ca = identity(NULL, 4, NULL);
    struct identity client = identity(&ca, 2, GNUTLS_KP_TLS_WWW_CLIENT), gateway = identity(&ca, 3, GNUTLS_KP_TLS_WWW_SERVER);
    invalid_profiles(&ca, &client, &wrong_ca);
    sealed_reference_tests(&ca, &client, &gateway);
    for (unsigned i = 0; i < 3; i++) for (enum mode mode = PACKETS; mode <= TRUNCATED_CHUNK; mode++)
        adapter_run(mode, &ca, &wrong_ca, &client, &gateway, false, true);
    adapter_run(PACKETS, &ca, &wrong_ca, &client, &gateway, true, true);
    concurrent_isolation();
    free_identity(&gateway); free_identity(&client); free_identity(&wrong_ca); free_identity(&ca);
    puts("PASS: 42 owned adapter TLS/packet/cancel/origin/auth rejection lifecycles, pre-start cancel, concurrent isolated authorities/packets, bounded inputs and sealed FDs");
    return 0;
}
