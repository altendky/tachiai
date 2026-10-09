#include <config.h>
#if defined(HAVE_VHOST) || !defined(OPENCONNECT_GNUTLS)
#error This owned fixture requires GnuTLS and disabled kernel vhost support.
#endif
#include <openconnect.h>
#include <gnutls/gnutls.h>
#include <gnutls/x509.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

// Linux host proof only. No OS TUN, system route, real credential or provider.
static void require(int ok, const char *label) {
    if (!ok) { fprintf(stderr, "FAIL: %s\n", label); exit(1); }
}
static void crypto(int result) { require(result >= 0, "fixture crypto operation"); }
static void quiet(void *data, int level, const char *format, ...) { (void)data; (void)level; (void)format; }
static int reject_cert(void *data, const char *reason) {
    (void)reason; atomic_fetch_add((atomic_int *)data, 1); return -EINVAL;
}
struct identity { gnutls_x509_crt_t cert; gnutls_x509_privkey_t key; gnutls_datum_t pem, secret; };
static struct identity identity(struct identity *ca, unsigned serial, const char *purpose) {
    struct identity id = {0};
    crypto(gnutls_x509_privkey_init(&id.key));
    crypto(gnutls_x509_privkey_generate(id.key, GNUTLS_PK_ECDSA, 256, 0));
    crypto(gnutls_x509_crt_init(&id.cert));
    crypto(gnutls_x509_crt_set_version(id.cert, 3));
    unsigned char number = (unsigned char)serial;
    crypto(gnutls_x509_crt_set_serial(id.cert, &number, 1));
    crypto(gnutls_x509_crt_set_activation_time(id.cert, time(NULL)-60));
    crypto(gnutls_x509_crt_set_expiration_time(id.cert, time(NULL)+3600));
    crypto(gnutls_x509_crt_set_dn(id.cert, "CN=owned-fixture", NULL));
    crypto(gnutls_x509_crt_set_key(id.cert, id.key));
    crypto(gnutls_x509_crt_set_basic_constraints(id.cert, ca == NULL, -1));
    crypto(gnutls_x509_crt_set_key_usage(id.cert, GNUTLS_KEY_DIGITAL_SIGNATURE | (ca ? 0 : GNUTLS_KEY_KEY_CERT_SIGN)));
    if (purpose) crypto(gnutls_x509_crt_set_key_purpose_oid(id.cert, purpose, 0));
    if (purpose && !strcmp(purpose, GNUTLS_KP_TLS_WWW_SERVER))
        crypto(gnutls_x509_crt_set_subject_alt_name(id.cert, GNUTLS_SAN_DNSNAME, "fixture.invalid", 15, 0));
    crypto(gnutls_x509_crt_sign2(id.cert, ca ? ca->cert : id.cert, ca ? ca->key : id.key, GNUTLS_DIG_SHA256, 0));
    crypto(gnutls_x509_crt_export2(id.cert, GNUTLS_X509_FMT_PEM, &id.pem));
    crypto(gnutls_x509_privkey_export2(id.key, GNUTLS_X509_FMT_PEM, &id.secret));
    return id;
}
static void free_identity(struct identity *id) {
    explicit_bzero(id->secret.data, id->secret.size);
    gnutls_free(id->secret.data); gnutls_free(id->pem.data);
    gnutls_x509_crt_deinit(id->cert); gnutls_x509_privkey_deinit(id->key);
}
static int memory_file(const gnutls_datum_t *data, char path[64]) {
    int fd = memfd_create("tachiai-owned-fixture", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    require(fd >= 0, "memfd create");
    require(write(fd, data->data, data->size) == (ssize_t)data->size, "memfd write");
    require(fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL) == 0, "memfd seal");
    snprintf(path, 64, "/proc/self/fd/%d", fd);
    return fd;
}
static void wait_readable(int fd) {
    struct pollfd p = {.fd = fd, .events = POLLIN};
    require(poll(&p, 1, 5000) == 1 && (p.revents & POLLIN), "bounded fixture readiness");
}
static void join(pthread_t thread, const char *label) {
    struct timespec until; clock_gettime(CLOCK_REALTIME, &until); until.tv_sec += 5;
    require(pthread_timedjoin_np(thread, NULL, &until) == 0, label);
}
static void tls_write(gnutls_session_t tls, const void *data, size_t count) {
    const unsigned char *p = data;
    while (count) { ssize_t n = gnutls_record_send(tls, p, count); require(n > 0, "fixture TLS write"); p += n; count -= (size_t)n; }
}
static int tls_read(gnutls_session_t tls, void *data, size_t count) {
    unsigned char *p = data;
    while (count) { ssize_t n = gnutls_record_recv(tls, p, count); if (n <= 0) return -1; p += n; count -= (size_t)n; }
    return 0;
}
static int request(gnutls_session_t tls, char header[8192]) {
    size_t n = 0;
    while (n < 8191) {
        if (tls_read(tls, header+n, 1)) return -1;
        header[++n] = 0;
        if (n >= 4 && !memcmp(header+n-4, "\r\n\r\n", 4)) break;
    }
    require(n < 8191, "bounded request headers");
    const char *length = strstr(header, "Content-Length:");
    if (length) {
        unsigned long size = strtoul(length+15, NULL, 10);
        require(size <= 8192, "bounded auth body");
        char body[8192]; if (tls_read(tls, body, size)) return -1;
    }
    return 0;
}
enum mode { PACKETS, CANCEL_AUTH, WRONG_HOST, WRONG_CA, AUTH_FORM, CANCEL_TLS,
    AUTH_SSO, HOST_SCAN, LEGACY_CSD, REDIRECT, HTTP_AUTH, XML_DTD,
    TRUNCATED_LENGTH, TRUNCATED_CHUNK };
struct fixture {
    int listener, port, seen[2], ready[2], packet[2];
    enum mode mode;
    gnutls_certificate_credentials_t credentials;
    struct openconnect_info *vpn;
    atomic_int rejects;
    int result;
    const unsigned char *payload;
};
static const unsigned char packet[] = {0x45, 0, 0, 20, 0, 1, 0, 0, 64, 17, 0, 0, 10, 2, 0, 2, 10, 2, 0, 1};
static void *server(void *argument) {
    struct fixture *f = argument;
    int c = accept4(f->listener, NULL, NULL, SOCK_CLOEXEC);
    require(c >= 0, "owned listener accept");
    if (f->mode == CANCEL_TLS) {
        unsigned char data[1024];
        struct timeval bound = {.tv_sec = 30};
        require(setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &bound, sizeof(bound)) == 0, "bounded TLS fixture socket");
        require(recv(c, data, 1, 0) == 1, "ClientHello started before cancellation");
        require(write(f->seen[1], "a", 1) == 1, "TLS cancellation readiness");
        while (recv(c, data, sizeof(data), 0) > 0) {}
        close(c); return NULL;
    }
    gnutls_session_t tls;
    crypto(gnutls_init(&tls, GNUTLS_SERVER));
    crypto(gnutls_priority_set_direct(tls, "NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2", NULL));
    crypto(gnutls_credentials_set(tls, GNUTLS_CRD_CERTIFICATE, f->credentials));
    gnutls_certificate_server_set_request(tls, GNUTLS_CERT_REQUIRE);
    gnutls_transport_set_int(tls, c); gnutls_handshake_set_timeout(tls, 5000); gnutls_record_set_timeout(tls, 5000);
    int result = gnutls_handshake(tls);
    if (f->mode == WRONG_HOST || f->mode == WRONG_CA) {
        // A peer can reject just after the server finishes its TLS handshake.
        if (result >= 0) { char header[8192]; require(request(tls, header) < 0, "bad TLS receives no auth request"); }
        goto done;
    }
    crypto(result);
    unsigned status = 0; crypto(gnutls_certificate_verify_peers2(tls, &status));
    require(status == 0, "actual client certificate accepted by owned CA");
    char header[8192]; require(request(tls, header) == 0, "certificate-only initial request");
    require(!strncmp(header, "POST ", 5), "initial AnyConnect XML POST");
    require(!strstr(header, "Authorization:"), "HTTP auth disabled");
    require(write(f->seen[1], "a", 1) == 1, "authentication readiness");
    if (f->mode == CANCEL_AUTH) { char byte; (void)gnutls_record_recv(tls, &byte, 1); goto done; }
    if (f->mode == TRUNCATED_LENGTH || f->mode == TRUNCATED_CHUNK) {
        const char *response = f->mode == TRUNCATED_LENGTH ?
            "HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n<config-auth" :
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n400\r\n<config-auth";
        tls_write(tls, response, strlen(response));
        crypto(gnutls_bye(tls, GNUTLS_SHUT_WR));
        require(write(f->seen[1], "e", 1) == 1, "truncated TLS EOF readiness");
        goto done;
    }
    if (f->mode == REDIRECT || f->mode == HTTP_AUTH) {
        char denial[256];
        int n = f->mode == REDIRECT ? snprintf(denial, sizeof(denial),
            "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:%d/different\r\nContent-Length: 0\r\n\r\n", f->port) :
            snprintf(denial, sizeof(denial), "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=owned\r\nContent-Length: 0\r\n\r\n");
        tls_write(tls, denial, (size_t)n);
        require(request(tls, header) < 0, "redirect/HTTP authentication sends no further request");
        struct pollfd listener = {.fd = f->listener, .events = POLLIN};
        require(poll(&listener, 1, 250) == 0, "redirect never reconnects or discloses client certificate to second authority");
        goto done;
    }
    const char *body = f->mode == AUTH_FORM ?
        "<config-auth><auth id=\"main\"><form method=\"post\" action=\"/\"><input type=\"password\" name=\"password\"/></form></auth></config-auth>" :
        "<config-auth client=\"vpn\" type=\"complete\"><session-token>owned-runtime-only-cookie</session-token><auth id=\"success\"/><config/></config-auth>";
    if (f->mode == AUTH_SSO) body = "<config-auth><auth id=\"main\"><sso-v2-login>https://different.invalid/</sso-v2-login></auth></config-auth>";
    if (f->mode == HOST_SCAN) body = "<config-auth><host-scan><host-scan-base-uri>/scan</host-scan-base-uri><host-scan-wait-uri>/wait</host-scan-wait-uri></host-scan><auth id=\"success\"/></config-auth>";
    if (f->mode == LEGACY_CSD) body = "<config-auth><auth id=\"success\"><csdLinux stuburl=\"/stub\" starturl=\"/start\" waiturl=\"/wait\"/></auth></config-auth>";
    if (f->mode == XML_DTD) body = "<!DOCTYPE config-auth [<!ENTITY owned 'owned'>]><config-auth><auth id=\"success\"/></config-auth>";
    char response[1024]; int size = snprintf(response, sizeof(response), "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: %zu\r\n\r\n%s", strlen(body), body);
    tls_write(tls, response, (size_t)size);
    if (f->mode == AUTH_FORM || f->mode >= AUTH_SSO) {
        require(request(tls, header) < 0, "unsupported authentication sends no request/script fetch"); goto done;
    }
    // Certificate negotiation may close the initial HTTPS connection before
    // CSTP. Accept a fresh TLS connection as well as HTTP keepalive reuse.
    int read_result = request(tls, header);
    if (read_result < 0) {
        gnutls_deinit(tls); close(c);
        c = accept4(f->listener, NULL, NULL, SOCK_CLOEXEC); require(c >= 0, "CSTP listener accept");
        crypto(gnutls_init(&tls, GNUTLS_SERVER));
        crypto(gnutls_priority_set_direct(tls, "NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2", NULL));
        crypto(gnutls_credentials_set(tls, GNUTLS_CRD_CERTIFICATE, f->credentials));
        gnutls_certificate_server_set_request(tls, GNUTLS_CERT_REQUIRE);
        gnutls_transport_set_int(tls, c); gnutls_handshake_set_timeout(tls, 5000); gnutls_record_set_timeout(tls, 5000);
        crypto(gnutls_handshake(tls)); status = 0; crypto(gnutls_certificate_verify_peers2(tls, &status));
        require(status == 0, "client certificate reload on CSTP reconnect");
        read_result = request(tls, header);
    }
    require(read_result == 0 && !strncmp(header, "CONNECT /CSCOSSLC/tunnel ", 24), "owned CSTP request");
    require(strstr(header, "Cookie: webvpn=owned-runtime-only-cookie") != NULL, "cookie stays inside owned VPN session");
    const char *negotiated = "HTTP/1.1 200 CONNECTED\r\nX-CSTP-Version: 1\r\nX-CSTP-MTU: 1280\r\nX-CSTP-Address: 10.2.0.2\r\nX-CSTP-Netmask: 255.255.255.0\r\nX-CSTP-DNS: 10.2.0.1\r\n\r\n";
    tls_write(tls, negotiated, strlen(negotiated));
    unsigned char frame[8 + sizeof(packet)];
    require(tls_read(tls, frame, sizeof(frame)) == 0, "CSTP outgoing frame");
    const unsigned char prefix[] = {'S', 'T', 'F', 1, 0, sizeof(packet), 0, 0};
    require(!memcmp(frame, prefix, 8) && !memcmp(frame+8, f->payload ? f->payload : packet, sizeof(packet)), "raw packet boundary and bytes");
    tls_write(tls, frame, sizeof(frame));
    // Cancellation emits a protocol BYE, then caller free closes TLS.
    while (gnutls_record_recv(tls, frame, sizeof(frame)) > 0) {}
done:
    gnutls_deinit(tls); close(c); return NULL;
}
static int resolve(void *argument, const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    // cbdata is the rejects field; no DNS or redirect to another authority.
    struct fixture *f = (struct fixture *)((char *)argument - offsetof(struct fixture, rejects));
    const char *expected = f->mode == WRONG_HOST ? "wrong.invalid" : "fixture.invalid";
    char port[16]; snprintf(port, sizeof(port), "%d", f->port);
    if (strcmp(node, expected) || strcmp(service, port)) return EAI_FAIL;
    struct addrinfo numeric = *hints; numeric.ai_flags |= AI_NUMERICHOST | AI_NUMERICSERV;
    return getaddrinfo("127.0.0.1", port, &numeric, result);
}
static void *worker(void *argument) {
    struct fixture *f = argument;
    f->result = openconnect_obtain_cookie(f->vpn);
    if (f->mode != PACKETS || f->result) return NULL;
    f->result = openconnect_make_cstp_connection(f->vpn);
    if (f->result) return NULL;
    const struct oc_ip_info *info;
    require(openconnect_get_ip_info(f->vpn, &info, NULL, NULL) == 0 &&
        !strcmp(info->addr, "10.2.0.2") && !strcmp(info->dns[0], "10.2.0.1") && info->mtu == 1280, "negotiated snapshot");
    require(openconnect_setup_tun_fd(f->vpn, f->packet[0]) == 0, "caller datagram FD accepted");
    require((fcntl(f->packet[0], F_GETFL) & O_NONBLOCK) && (fcntl(f->packet[0], F_GETFD) & FD_CLOEXEC), "borrowed FD flags");
    require(write(f->ready[1], "t", 1) == 1, "packet readiness");
    f->result = openconnect_mainloop(f->vpn, 0, RECONNECT_INTERVAL_MIN);
    return NULL;
}
static int fd_count(void) {
    DIR *dir = opendir("/proc/self/fd"); require(dir != NULL, "FD inventory");
    int count = 0; while (readdir(dir)) count++; closedir(dir); return count;
}
static void run(enum mode mode, struct identity *ca, struct identity *wrong_ca, struct identity *client, struct identity *gateway) {
    struct fixture f = {.mode = mode}; atomic_init(&f.rejects, 0);
    int before = fd_count();
    f.listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0); require(f.listener >= 0, "listener create");
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    require(bind(f.listener, (struct sockaddr *)&address, sizeof(address)) == 0 && listen(f.listener, 1) == 0, "loopback only listener");
    socklen_t length = sizeof(address); require(getsockname(f.listener, (struct sockaddr *)&address, &length) == 0, "owned port");
    f.port = ntohs(address.sin_port);
    require(pipe2(f.seen, O_CLOEXEC) == 0 && pipe2(f.ready, O_CLOEXEC) == 0 &&
        socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, f.packet) == 0, "owned lifecycle descriptors");
    crypto(gnutls_certificate_allocate_credentials(&f.credentials));
    crypto(gnutls_certificate_set_x509_key_mem(f.credentials, &gateway->pem, &gateway->secret, GNUTLS_X509_FMT_PEM));
    crypto(gnutls_certificate_set_x509_trust_mem(f.credentials, &ca->pem, GNUTLS_X509_FMT_PEM));
    char ca_path[64], cert_path[64], key_path[64];
    int ca_fd = memory_file(mode == WRONG_CA ? &wrong_ca->pem : &ca->pem, ca_path);
    int cert_fd = memory_file(&client->pem, cert_path), key_fd = memory_file(&client->secret, key_path);
    f.vpn = openconnect_vpninfo_new("tachiai-owned-fixture", reject_cert, NULL, NULL, quiet, &f.rejects);
    require(f.vpn != NULL, "per-session construction");
    int command = openconnect_setup_cmd_pipe(f.vpn); require(command >= 0, "library command pipe");
    // Public pipe API does not set CLOEXEC itself: adapter must do this.
    require(fcntl(command, F_SETFD, FD_CLOEXEC) == 0, "command writer CLOEXEC");
    require(openconnect_set_protocol(f.vpn, "anyconnect") == 0 && openconnect_set_reported_os(f.vpn, "android") == 0 &&
        openconnect_set_http_auth(f.vpn, "") == 0 && openconnect_set_compression_mode(f.vpn, OC_COMPRESSION_MODE_NONE) == 0 &&
        openconnect_disable_dtls(f.vpn) == 0 && openconnect_disable_ipv6(f.vpn) == 0, "bounded certificate-only settings");
    openconnect_set_system_trust(f.vpn, 0);
    require(openconnect_set_cafile(f.vpn, ca_path) == 0 && openconnect_set_client_cert(f.vpn, cert_path, key_path) == 0, "memory path configuration");
    openconnect_override_getaddrinfo(f.vpn, resolve);
    char url[96]; snprintf(url, sizeof(url), "https://%s:%d/", mode == WRONG_HOST ? "wrong.invalid" : "fixture.invalid", f.port);
    require(openconnect_parse_url(f.vpn, url) == 0, "configured authority");
    pthread_t server_thread, worker_thread;
    require(pthread_create(&server_thread, NULL, server, &f) == 0 && pthread_create(&worker_thread, NULL, worker, &f) == 0, "owned worker start");
    if (mode == CANCEL_AUTH || mode == CANCEL_TLS) { wait_readable(f.seen[0]); require(write(command, "x", 1) == 1, "preparation cancel command"); }
    if (mode == PACKETS) {
        wait_readable(f.ready[0]);
        require(send(f.packet[1], packet, sizeof(packet), 0) == sizeof(packet), "datagram send");
        wait_readable(f.packet[1]); unsigned char received[128];
        require(recv(f.packet[1], received, sizeof(received), 0) == sizeof(packet) && !memcmp(received, packet, sizeof(packet)), "CSTP/datagram roundtrip");
        require(write(command, "x", 1) == 1, "mainloop cancellation command");
    }
    join(worker_thread, "bounded native worker join");
    require(mode == PACKETS ? f.result == -EINTR : mode == CANCEL_AUTH || mode == CANCEL_TLS ? f.result == 1 : f.result < 0,
        "expected cancellation or rejection");
    if (mode == WRONG_HOST || mode == WRONG_CA) require(atomic_load(&f.rejects) == 1, "failed CA/hostname callback always rejects");
    require(fcntl(f.packet[0], F_GETFD) >= 0 && fcntl(f.packet[1], F_GETFD) >= 0, "caller owns both packet FDs after mainloop");
    openconnect_vpninfo_free(f.vpn);
    require(fcntl(command, F_GETFD) == -1 && errno == EBADF, "library owns command FD after free");
    require(fcntl(f.packet[0], F_GETFD) >= 0, "library free must not close caller packet FD");
    close(f.packet[0]); close(f.packet[1]); close(ca_fd); close(cert_fd); close(key_fd);
    join(server_thread, "bounded owned server join"); close(f.listener);
    for (int i = 0; i < 2; i++) { close(f.seen[i]); close(f.ready[i]); }
    gnutls_certificate_free_credentials(f.credentials);
    require(fd_count() == before, "no owned FD leak");
}
static int construction_probe(void) {
    int saved = dup(STDIN_FILENO), sentinel[2]; require(saved >= 0 && pipe2(sentinel, O_CLOEXEC) == 0, "stdin sentinel setup");
    int preserved = 1;
    for (int force_pipe_failure = 0; force_pipe_failure <= 1; force_pipe_failure++) {
        require(dup2(sentinel[0], STDIN_FILENO) == STDIN_FILENO, "owned sentinel FD0");
        atomic_int rejects; atomic_init(&rejects, 0);
        struct openconnect_info *vpn = openconnect_vpninfo_new("tachiai-owned-construction", reject_cert, NULL, NULL, quiet, &rejects);
        require(vpn != NULL, "negative construction session");
        struct rlimit original;
        if (force_pipe_failure) {
            require(getrlimit(RLIMIT_NOFILE, &original) == 0, "fixture resource limit snapshot");
            struct rlimit exhausted = original; exhausted.rlim_cur = 0;
            require(setrlimit(RLIMIT_NOFILE, &exhausted) == 0, "owned process FD exhaustion");
            require(openconnect_setup_cmd_pipe(vpn) < 0, "actual command-pipe construction failure");
        }
        openconnect_vpninfo_free(vpn);
        if (force_pipe_failure) require(setrlimit(RLIMIT_NOFILE, &original) == 0, "restore fixture resource limit");
        if (fcntl(STDIN_FILENO, F_GETFD) < 0) preserved = 0;
    }
    require(dup2(saved, STDIN_FILENO) == STDIN_FILENO, "restore fixture stdin"); close(saved); close(sentinel[0]); close(sentinel[1]);
    if (!preserved) { fprintf(stderr, "FAIL: upstream free before command setup closed owned FD0 sentinel\n"); return 2; }
    puts("PASS: no-command and failed-command construction preserve unrelated FD0"); return 0;
}
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    const char *version = openconnect_get_version();
    require(!strcmp(version, "v9.21") || !strcmp(version, "v9.21-unknown") ||
        !strcmp(version, "9.21-tachiai-certificate-only-1"), "pinned libopenconnect version");
    crypto(openconnect_init_ssl());
    if (argc == 2 && !strcmp(argv[1], "--construction-probe")) return construction_probe();
    int only_tls = argc == 2 && !strcmp(argv[1], "--cancel-tls-probe");
    require(argc == 1 || only_tls, "known fixture arguments");
    struct identity ca = identity(NULL, 1, NULL), wrong_ca = identity(NULL, 4, NULL);
    struct identity client = identity(&ca, 2, GNUTLS_KP_TLS_WWW_CLIENT), gateway = identity(&ca, 3, GNUTLS_KP_TLS_WWW_SERVER);
    if (only_tls) {
        run(CANCEL_TLS, &ca, &wrong_ca, &client, &gateway);
    } else {
        for (int iteration = 0; iteration < 3; iteration++) {
            for (enum mode mode = PACKETS; mode <= CANCEL_TLS; mode++) run(mode, &ca, &wrong_ca, &client, &gateway);
        }
    }
    free_identity(&gateway); free_identity(&client); free_identity(&wrong_ca); free_identity(&ca);
    puts(only_tls ? "PASS: TLS handshake cancellation joins its worker" : "PASS: 18 owned certificate/memfd/CSTP/cancel/rejection lifecycles; no FD leaks");
    return 0;
}
