#ifndef TACHIAI_OPENCONNECT_ADAPTER_H
#define TACHIAI_OPENCONNECT_ADAPTER_H
#include <stddef.h>
#include <stdint.h>

/* Internal C ABI. No logging, script, browser, cookie, or raw
 * OpenConnect handle API. Caller inputs must remain valid until create returns. */
enum toc_status { TOC_OK = 0, TOC_PENDING, TOC_READY, TOC_CANCELLED,
    TOC_INVALID, TOC_RESOURCE, TOC_FAILED, TOC_BUSY, TOC_TIMEOUT };
struct toc_bytes { const void *data; size_t size; };
struct toc_profile {
    struct toc_bytes endpoint, ca_pem, client_pem, key_pem;
    /* Explicit runtime bootstrap; never resolve or dial via system fallback. */
    const char *bootstrap_ipv4;
};
struct toc_snapshot { uint32_t ipv4, dns[3]; unsigned dns_count, mtu; };
struct toc_session;

/* Total profile bytes <= 8192. HTTPS ASCII endpoint only, no userinfo/query/
 * fragment, required CA and matching unencrypted client PEM/key. Creates the
 * cancellation channel and sealed memory files BEFORE any network operation. */
enum toc_status toc_create(const struct toc_profile *, struct toc_session **);
/* Transfer the sole peer packet FD once. Caller closes it even after cancel;
 * datagram sockets do not promise EOF. Adapter owns the other endpoint. */
enum toc_status toc_take_packet_fd(struct toc_session *, int *);
enum toc_status toc_start(struct toc_session *);
enum toc_status toc_wait(struct toc_session *, unsigned timeout_ms,
    struct toc_snapshot *);
/* Cancellation is safe concurrently with start/wait/join until destroy.
 * Caller must exclude all API access before destroy frees the opaque handle. */
void toc_cancel(struct toc_session *);
enum toc_status toc_join(struct toc_session *, unsigned timeout_ms);
/* Returns BUSY until the worker has been joined. No active-worker free. */
enum toc_status toc_destroy(struct toc_session *);

#endif
