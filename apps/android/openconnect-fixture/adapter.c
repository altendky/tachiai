#define _GNU_SOURCE 1
#include "adapter.h"
#include "memory_file.h"
#include <openconnect.h>
#include <gnutls/gnutls.h>
#include <gnutls/x509.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

struct toc_session {
    pthread_mutex_t lock;
    pthread_cond_t changed;
    pthread_t worker;
    bool started, finished, joined, joining, cancelled;
    enum toc_status status;
    struct openconnect_info *vpn;
    int command, packet, peer, files[3];
    char host[254], port[6], bootstrap[INET_ADDRSTRLEN];
    struct toc_snapshot snapshot;
};
static pthread_once_t tls_once = PTHREAD_ONCE_INIT;
static int tls_result;
static void tls_init(void) { tls_result = openconnect_init_ssl(); }
static void quiet(void *data, int level, const char *format, ...) {
    (void)data; (void)level; (void)format;
}
static int reject_cert(void *data, const char *reason) {
    (void)data; (void)reason; return -EINVAL;
}
static int reject_form(void *data, struct oc_auth_form *form) {
    (void)data; (void)form; return OC_FORM_RESULT_CANCELLED;
}
static bool ascii_alnum(unsigned char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}
static bool endpoint(struct toc_session *s, struct toc_bytes bytes, char url[513]) {
    if (bytes.size < 10 || bytes.size > 512 || !bytes.data || memchr(bytes.data, 0, bytes.size)) return false;
    memcpy(url, bytes.data, bytes.size); url[bytes.size] = 0;
    if (strncmp(url, "https://", 8)) return false;
    const char *end = strchr(url + 8, '/');
    if (!end) end = url + bytes.size;
    const char *colon = memchr(url + 8, ':', (size_t)(end - url - 8));
    const char *host_end = colon ? colon : end;
    size_t host_size = (size_t)(host_end - url - 8);
    if (!host_size || host_size >= sizeof(s->host) || url[8] == '.' || host_end[-1] == '.') return false;
    for (const char *p = url + 8; p < host_end; p++) {
        if (!ascii_alnum((unsigned char)*p) && *p != '-' && *p != '.') return false;
    }
    memcpy(s->host, url + 8, host_size); s->host[host_size] = 0;
    unsigned port = 443;
    if (colon) {
        port = 0;
        if (end - colon < 2 || end - colon > 6) return false;
        for (const char *p = colon + 1; p < end; p++) {
            if (*p < '0' || *p > '9') return false;
            port = port * 10 + (unsigned)(*p - '0');
        }
        if (!port || port > 65535) return false;
    }
    snprintf(s->port, sizeof(s->port), "%u", port);
    if (strstr(end, "..")) return false;
    for (const char *p = end; *p; p++) {
        if (!ascii_alnum((unsigned char)*p) && *p != '/' && *p != '-' && *p != '_' && *p != '.') return false;
    }
    return true;
}
static bool key_material(const struct toc_profile *p) {
    gnutls_x509_crt_t ca = NULL, client = NULL;
    gnutls_x509_privkey_t key = NULL;
    bool valid = false;
    gnutls_datum_t ca_bytes = {(unsigned char *)p->ca_pem.data, (unsigned)p->ca_pem.size};
    gnutls_datum_t cert_bytes = {(unsigned char *)p->client_pem.data, (unsigned)p->client_pem.size};
    gnutls_datum_t key_bytes = {(unsigned char *)p->key_pem.data, (unsigned)p->key_pem.size};
    if (gnutls_x509_crt_init(&ca) < 0 || gnutls_x509_crt_init(&client) < 0 || gnutls_x509_privkey_init(&key) < 0) goto out;
    if (gnutls_x509_crt_import(ca, &ca_bytes, GNUTLS_X509_FMT_PEM) < 0 ||
        gnutls_x509_crt_get_ca_status(ca, NULL) != 1 ||
        gnutls_x509_crt_import(client, &cert_bytes, GNUTLS_X509_FMT_PEM) < 0 ||
        gnutls_x509_privkey_import(key, &key_bytes, GNUTLS_X509_FMT_PEM) < 0) goto out;
    unsigned char cert_id[64], key_id[64]; size_t cn = sizeof(cert_id), kn = sizeof(key_id);
    valid = gnutls_x509_crt_get_key_id(client, 0, cert_id, &cn) >= 0 &&
        gnutls_x509_privkey_get_key_id(key, 0, key_id, &kn) >= 0 && cn == kn && !memcmp(cert_id, key_id, cn);
out:
    if (key) gnutls_x509_privkey_deinit(key);
    if (client) gnutls_x509_crt_deinit(client);
    if (ca) gnutls_x509_crt_deinit(ca);
    return valid;
}
static int resolve(void *argument, const char *node, const char *service,
    const struct addrinfo *hints, struct addrinfo **result) {
    struct toc_session *s = argument;
    if (strcmp(node, s->host) || strcmp(service, s->port)) return EAI_FAIL;
    struct addrinfo numeric = *hints;
    numeric.ai_flags |= AI_NUMERICHOST | AI_NUMERICSERV;
    numeric.ai_family = AF_INET;
    return getaddrinfo(s->bootstrap, s->port, &numeric, result);
}
/* lock held, or creation has not yet published the session. No concurrent
 * command write can race closing the library-owned command read endpoint. */
static void release_native(struct toc_session *s) {
    s->command = -1;
    if (s->vpn) { openconnect_vpninfo_free(s->vpn); s->vpn = NULL; }
    if (s->packet >= 0) { close(s->packet); s->packet = -1; }
    for (unsigned i = 0; i < 3; i++) if (s->files[i] >= 0) { close(s->files[i]); s->files[i] = -1; }
}
enum toc_status toc_create(const struct toc_profile *p, struct toc_session **output) {
    if (!output) return TOC_INVALID;
    *output = NULL;
    if (!p || !p->bootstrap_ipv4) return TOC_INVALID;
    struct toc_bytes fields[] = {p->endpoint, p->ca_pem, p->client_pem, p->key_pem};
    size_t total = 0;
    for (unsigned i = 0; i < 4; i++) {
        if (!fields[i].data || !fields[i].size || fields[i].size > 8192 - total ||
            memchr(fields[i].data, 0, fields[i].size)) return TOC_INVALID;
        total += fields[i].size;
    }
    struct in_addr bootstrap;
    if (strnlen(p->bootstrap_ipv4, INET_ADDRSTRLEN) >= INET_ADDRSTRLEN ||
        inet_pton(AF_INET, p->bootstrap_ipv4, &bootstrap) != 1) return TOC_INVALID;
    if (strcmp(openconnect_get_version(), "9.21-tachiai-certificate-only-2")) return TOC_RESOURCE;
    if (pthread_once(&tls_once, tls_init) || tls_result || !key_material(p)) return TOC_INVALID;
    struct toc_session *s = calloc(1, sizeof(*s));
    if (!s) return TOC_RESOURCE;
    s->command = s->packet = s->peer = -1;
    for (unsigned i = 0; i < 3; i++) s->files[i] = -1;
    char url[513], paths[3][64];
    if (!endpoint(s, p->endpoint, url)) { free(s); return TOC_INVALID; }
    memcpy(s->bootstrap, p->bootstrap_ipv4, strlen(p->bootstrap_ipv4) + 1);
    if (pthread_mutex_init(&s->lock, NULL)) { free(s); return TOC_RESOURCE; }
    pthread_condattr_t attr;
    if (pthread_condattr_init(&attr)) { pthread_mutex_destroy(&s->lock); free(s); return TOC_RESOURCE; }
    int cond_result = pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    if (!cond_result) cond_result = pthread_cond_init(&s->changed, &attr);
    pthread_condattr_destroy(&attr);
    if (cond_result) { pthread_mutex_destroy(&s->lock); free(s); return TOC_RESOURCE; }
    s->status = TOC_PENDING;
    for (unsigned i = 0; i < 3; i++) {
        s->files[i] = toc_memory_file(fields[i + 1].data, fields[i + 1].size, paths[i]);
        if (s->files[i] < 0) goto fail;
    }
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, pair)) goto fail;
    s->packet = pair[0]; s->peer = pair[1];
    s->vpn = openconnect_vpninfo_new("tachiai-internal-fixture", reject_cert, NULL, reject_form, quiet, s);
    if (!s->vpn) goto fail;
    s->command = openconnect_setup_cmd_pipe(s->vpn);
    if (s->command < 0 || openconnect_set_protocol(s->vpn, "anyconnect") ||
        openconnect_set_reported_os(s->vpn, "android") || openconnect_set_http_auth(s->vpn, "") ||
        openconnect_set_compression_mode(s->vpn, OC_COMPRESSION_MODE_NONE) ||
        openconnect_disable_dtls(s->vpn) || openconnect_disable_ipv6(s->vpn) ||
        openconnect_set_cafile(s->vpn, paths[0]) || openconnect_set_client_cert(s->vpn, paths[1], paths[2]) ||
        openconnect_parse_url(s->vpn, url)) goto fail;
    openconnect_set_system_trust(s->vpn, 0);
    openconnect_override_getaddrinfo(s->vpn, resolve);
    *output = s;
    return TOC_OK;
fail:
    release_native(s);
    if (s->peer >= 0) close(s->peer);
    pthread_cond_destroy(&s->changed); pthread_mutex_destroy(&s->lock); free(s);
    return TOC_RESOURCE;
}
static bool snapshot(struct toc_session *s) {
    const struct oc_ip_info *info;
    if (openconnect_get_ip_info(s->vpn, &info, NULL, NULL) || !info || !info->addr ||
        info->addr6 || info->netmask6 || info->split_dns || info->split_includes || info->split_excludes ||
        info->proxy_pac || info->mtu < 576 || info->mtu > 9000 ||
        inet_pton(AF_INET, info->addr, &s->snapshot.ipv4) != 1) return false;
    uint32_t address = ntohl(s->snapshot.ipv4);
    if (!address || address == UINT32_MAX || (address >> 24) == 127 || (address >> 28) >= 14) return false;
    s->snapshot.mtu = (unsigned)info->mtu;
    for (unsigned i = 0; i < 3; i++) if (info->dns[i]) {
        if (inet_pton(AF_INET, info->dns[i], &s->snapshot.dns[s->snapshot.dns_count]) != 1) return false;
        uint32_t dns = ntohl(s->snapshot.dns[s->snapshot.dns_count]);
        if (!dns || dns == UINT32_MAX || (dns >> 24) == 127 || (dns >> 28) >= 14) return false;
        s->snapshot.dns_count++;
    }
    return s->snapshot.dns_count > 0;
}
static void *run(void *argument) {
    struct toc_session *s = argument;
    pthread_mutex_lock(&s->lock);
    bool cancelled = s->cancelled;
    pthread_mutex_unlock(&s->lock);
    int result = cancelled ? 1 : openconnect_obtain_cookie(s->vpn);
    if (!result) result = openconnect_make_cstp_connection(s->vpn);
    if (!result && (!snapshot(s) || openconnect_setup_tun_fd(s->vpn, s->packet))) result = -EINVAL;
    if (!result) {
        pthread_mutex_lock(&s->lock);
        if (!s->cancelled) s->status = TOC_READY;
        pthread_cond_broadcast(&s->changed);
        pthread_mutex_unlock(&s->lock);
        result = openconnect_mainloop(s->vpn, 0, RECONNECT_INTERVAL_MIN);
    }
    pthread_mutex_lock(&s->lock);
    s->status = s->cancelled ? TOC_CANCELLED : TOC_FAILED;
    release_native(s);
    s->finished = true;
    pthread_cond_broadcast(&s->changed);
    pthread_mutex_unlock(&s->lock);
    return NULL;
}
enum toc_status toc_take_packet_fd(struct toc_session *s, int *output) {
    if (!s || !output) return TOC_INVALID;
    *output = -1;
    pthread_mutex_lock(&s->lock);
    enum toc_status result = s->peer >= 0 && !s->started ? TOC_OK : TOC_BUSY;
    if (result == TOC_OK) { *output = s->peer; s->peer = -1; }
    pthread_mutex_unlock(&s->lock);
    return result;
}
enum toc_status toc_start(struct toc_session *s) {
    if (!s) return TOC_INVALID;
    pthread_mutex_lock(&s->lock);
    enum toc_status result = TOC_BUSY;
    if (!s->started) {
        result = pthread_create(&s->worker, NULL, run, s) ? TOC_RESOURCE : TOC_OK;
        if (result == TOC_OK) s->started = true;
    }
    pthread_mutex_unlock(&s->lock);
    return result;
}
static bool deadline(unsigned ms, struct timespec *until) {
    if (ms > 30000 || clock_gettime(CLOCK_MONOTONIC, until)) return false;
    until->tv_sec += ms / 1000;
    until->tv_nsec += (long)(ms % 1000) * 1000000;
    if (until->tv_nsec >= 1000000000) { until->tv_sec++; until->tv_nsec -= 1000000000; }
    return true;
}
enum toc_status toc_wait(struct toc_session *s, unsigned timeout_ms, struct toc_snapshot *out) {
    struct timespec until;
    if (!s || !deadline(timeout_ms, &until)) return TOC_INVALID;
    pthread_mutex_lock(&s->lock);
    int result = 0;
    while (s->started && s->status == TOC_PENDING && !result)
        result = pthread_cond_timedwait(&s->changed, &s->lock, &until);
    enum toc_status status = result == ETIMEDOUT ? TOC_TIMEOUT : result ? TOC_RESOURCE : s->status;
    if (status == TOC_READY && out) *out = s->snapshot;
    pthread_mutex_unlock(&s->lock);
    return status;
}
void toc_cancel(struct toc_session *s) {
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (!s->cancelled) {
        s->cancelled = true;
        if (s->command >= 0) {
            unsigned char command = OC_CMD_CANCEL;
            ssize_t result;
            do { result = write(s->command, &command, 1); } while (result < 0 && errno == EINTR);
            /* Exactly one write to a nonblocking empty private pipe. */
            if (result != 1) s->status = TOC_FAILED;
        }
    }
    pthread_mutex_unlock(&s->lock);
}
enum toc_status toc_join(struct toc_session *s, unsigned timeout_ms) {
    struct timespec until;
    if (!s || !deadline(timeout_ms, &until)) return TOC_INVALID;
    pthread_mutex_lock(&s->lock);
    if (!s->started || s->joined) { pthread_mutex_unlock(&s->lock); return TOC_OK; }
    if (s->joining) { pthread_mutex_unlock(&s->lock); return TOC_BUSY; }
    s->joining = true;
    int result = 0;
    while (!s->finished && !result) result = pthread_cond_timedwait(&s->changed, &s->lock, &until);
    if (!result) result = pthread_join(s->worker, NULL);
    if (!result) s->joined = true;
    s->joining = false;
    pthread_mutex_unlock(&s->lock);
    return result == ETIMEDOUT ? TOC_TIMEOUT : result ? TOC_RESOURCE : TOC_OK;
}
enum toc_status toc_destroy(struct toc_session *s) {
    if (!s) return TOC_OK;
    pthread_mutex_lock(&s->lock);
    if (s->started && !s->joined) { pthread_mutex_unlock(&s->lock); return TOC_BUSY; }
    release_native(s);
    if (s->peer >= 0) close(s->peer);
    pthread_mutex_unlock(&s->lock);
    pthread_cond_destroy(&s->changed); pthread_mutex_destroy(&s->lock); free(s);
    return TOC_OK;
}
