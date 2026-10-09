/* Pure in-memory checks of the actual patched OpenConnect HTTP parser.
 * Link with the verified source tree's .libs/libopenconnect_la-*.o objects
 * and their existing GnuTLS/Nettle/XML/zlib dependencies. No TLS session,
 * socket, gateway, credential or network operation is created here. */
#include "config.h"
#include "openconnect-internal.h"
#include <limits.h>
#include <stdio.h>

enum { BODY_LIMIT = 65536, ANY_ERROR = INT_MIN };

struct memory_input {
    const char *data;
    size_t length, position, body_offset;
    const size_t *fragments;
    size_t fragment_count, fragment_index, fragment_left;
};

static unsigned cases;

static void require(int condition, const char *name) {
    if (!condition) {
        fprintf(stderr, "HTTP bounds fixture failed: %s\n", name);
        exit(1);
    }
}

static int memory_read(struct openconnect_info *vpninfo, char *buffer, size_t size) {
    struct memory_input *input = vpninfo->cbdata;
    if (input->position == input->length)
        return 0;
    size_t count = input->length - input->position;
    if (count > size)
        count = size;
    if (input->position < input->body_offset) {
        if (count > input->body_offset - input->position)
            count = input->body_offset - input->position;
    } else if (input->fragments) {
        if (!input->fragment_left) {
            require(input->fragment_index < input->fragment_count, "fragment script covers input");
            input->fragment_left = input->fragments[input->fragment_index++];
        }
        if (count > input->fragment_left)
            count = input->fragment_left;
        input->fragment_left -= count;
    }
    require(count > 0 && count <= INT_MAX, "memory callback makes bounded progress");
    memcpy(buffer, input->data + input->position, count);
    input->position += count;
    return (int)count;
}

static struct oc_text_buf *wire(const char *header) {
    struct oc_text_buf *response = buf_alloc();
    buf_append(response, "%s", header);
    require(!buf_error(response), "allocate owned response");
    return response;
}

static void repeat(struct oc_text_buf *response, char character, size_t length) {
    char block[4096];
    memset(block, character, sizeof(block));
    while (length) {
        size_t count = length < sizeof(block) ? length : sizeof(block);
        buf_append_bytes(response, block, (int)count);
        length -= count;
    }
    require(!buf_error(response), "construct bounded owned response");
}

static void expect(const char *name, struct oc_text_buf *response, int status,
                   int body_length, size_t body_offset,
                   const size_t *fragments, size_t fragment_count) {
    static const struct vpn_proto protocol = {.name = "owned-memory"};
    struct memory_input input = {.data = response->data, .length = (size_t)response->pos,
        .body_offset = body_offset, .fragments = fragments, .fragment_count = fragment_count};
    struct openconnect_info vpninfo = {.proto = &protocol, .ssl_fd = -1,
        .verbose = -1, .cbdata = &input, .ssl_read = memory_read};
    struct oc_text_buf *body = buf_alloc();
    require(!buf_error(response) && !buf_error(body), "owned buffers available");
    int result = process_http_response(&vpninfo, 0, NULL, body);
    if ((status == ANY_ERROR ? result >= 0 : result != status) ||
        body->pos > BODY_LIMIT || (status >= 0 && body->pos != body_length)) {
        fprintf(stderr, "%s: returned %d, body %d, consumed %zu/%zu\n",
                name, result, body->pos, input.position, input.length);
        exit(1);
    }
    require(vpninfo.ssl_fd == -1 && vpninfo.https_sess == NULL,
            "parser fixture never creates a socket or TLS session");
    buf_free(body);
    buf_free(response);
    cases++;
}

static void fixed_body(size_t length, int status) {
    struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\n");
    buf_append(response, "Content-Length: %zu\r\n\r\n", length);
    size_t offset = (size_t)response->pos;
    repeat(response, 'a', length);
    expect("fixed body bound", response, status, (int)length, offset, NULL, 0);
}

static void chunks(int overflow) {
    struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
    for (int index = 0; index < 2; index++) {
        buf_append(response, "8000\r\n");
        repeat(response, 'a', 32768);
        buf_append(response, "\r\n");
    }
    if (overflow)
        buf_append(response, "1\r\na\r\n");
    buf_append(response, "0\r\n\r\n");
    expect("cumulative chunk body bound", response, overflow ? -E2BIG : 200,
           BODY_LIMIT, 0, NULL, 0);
}

static void line_bound(size_t content_length, int status) {
    struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Pad: ");
    repeat(response, 'a', content_length - strlen("X-Pad: "));
    buf_append(response, "\r\n\r\n");
    expect("header line bound", response, status, 0, 0, NULL, 0);
}

static void aggregate_bound(size_t bytes, int status) {
    struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n");
    size_t remaining = bytes - (size_t)response->pos - 2;
    while (remaining) {
        size_t line = remaining < 1025 ? remaining : 1025;
        require(line >= 9, "aggregate fixture can encode padding line");
        buf_append(response, "X-Pad: ");
        repeat(response, 'a', line - 9);
        buf_append(response, "\r\n");
        remaining -= line;
    }
    buf_append(response, "\r\n");
    require((size_t)response->pos == bytes, "exact aggregate fixture byte count");
    expect("aggregate metadata bound", response, status, 0, 0, NULL, 0);
}

static void header_count(unsigned padding_count, int status) {
    struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n");
    for (unsigned index = 0; index < padding_count; index++)
        buf_append(response, "X-Pad: a\r\n");
    buf_append(response, "\r\n");
    expect("metadata line count", response, status, 0, 0, NULL, 0);
}

static void interim_count(unsigned count, int status) {
    struct oc_text_buf *response = wire("");
    for (unsigned index = 0; index < count; index++)
        buf_append(response, "HTTP/1.1 100 Continue\r\n\r\n");
    buf_append(response, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    expect("interim responses retain metadata budget", response, status, 0, 0, NULL, 0);
}

int main(void) {
    fixed_body(BODY_LIMIT, 200);
    fixed_body(BODY_LIMIT + 1, -E2BIG);
    chunks(0);
    chunks(1);
    line_bound(1023, 200);
    line_bound(1024, -E2BIG);
    aggregate_bound(8192, 200);
    aggregate_bound(8193, -E2BIG);
    header_count(61, 200);
    header_count(62, -E2BIG);
    interim_count(30, 200);
    interim_count(31, -E2BIG);

    const char *invalid[] = {
        "Content-Length: 1\r\nContent-Length: 1\r\n",
        "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n",
        "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n",
        "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n",
        "Content-Length: +1\r\n", "Content-Length: -1\r\n",
        "Content-Length: 1 0\r\n", "Content-Length: 1\t0\r\n",
        "Content-Length: \t \r\n", "Content-Length: 99999999999999999999\r\n"
    };
    for (size_t index = 0; index < sizeof(invalid) / sizeof(invalid[0]); index++) {
        struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\n");
        buf_append(response, "%s\r\n", invalid[index]);
        expect("ambiguous or invalid framing refused", response, ANY_ERROR, 0, 0, NULL, 0);
    }
    const char *ows[] = {"\t8", "  8", "8 ", " \t8 \t"};
    for (size_t index = 0; index < sizeof(ows) / sizeof(ows[0]); index++) {
        struct oc_text_buf *response = wire("HTTP/1.1 200 OK\r\n");
        buf_append(response, "Content-Length: %s\r\n\r\n12345678", ows[index]);
        expect("valid Content-Length HTTP OWS", response, 200, 8, 0, NULL, 0);
    }
    expect("fixed body early EOF", wire("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabc"),
           -EIO, 0, 0, NULL, 0);
    expect("chunk body early EOF", wire("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabc"),
           -EIO, 0, 0, NULL, 0);

    const size_t fragmented[] = {61440, 4095, 2};
    struct oc_text_buf *response = wire("HTTP/1.0 200 OK\r\n\r\n");
    size_t offset = (size_t)response->pos;
    repeat(response, 'a', BODY_LIMIT + 1);
    expect("fragmented HTTP/1.0 overshoot refused", response, -E2BIG, 0, offset,
           fragmented, sizeof(fragmented) / sizeof(fragmented[0]));
    response = wire("HTTP/1.0 200 OK\r\n\r\n");
    offset = (size_t)response->pos;
    repeat(response, 'a', BODY_LIMIT);
    expect("HTTP/1.0 exact body bound", response, 200, BODY_LIMIT, offset, NULL, 0);
    printf("Actual in-memory OpenConnect HTTP framing: %u cases passed\n", cases);
    return 0;
}
