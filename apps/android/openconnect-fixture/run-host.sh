#!/usr/bin/env bash
set -euo pipefail

# Host regression harness; the Android backend has a separate build recipe.
# All upstream sources/artifacts stay in the caller's disposable cache.
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_cache="${1:?Pass an absolute disposable cache directory}"
[[ "$task_cache" = /* ]]
mkdir -p "$task_cache"
task_version=9.21
task_sha256=5b32369467db6e5f317aa1ed12cfcbb81ed00bdbc765450b6bfcbdc300944a58
task_archive="$task_cache/openconnect-$task_version.tar.gz"
task_url="https://www.infradead.org/openconnect/download/openconnect-$task_version.tar.gz"
pkg-config --exists gnutls libxml-2.0 zlib
for task_tool in cc make patch tar sha256sum timeout; do command -v "$task_tool" >/dev/null; done
if [[ ! -f "$task_archive" ]]; then
  curl --fail --location --proto '=https' --tlsv1.2 --max-time 60 "$task_url" --output "$task_archive"
fi
printf '%s  %s\n' "$task_sha256" "$task_archive" | sha256sum --check --status
task_build="$(mktemp -d "$task_cache/openconnect-proof.XXXXXXXXXX")"
tar -xzf "$task_archive" -C "$task_build"
task_source="$task_build/openconnect-$task_version"
cd "$task_source"
./configure --disable-nls --disable-flask-tests --disable-vhost-net \
  --without-lz4 --without-libproxy --without-stoken --without-libpcsclite \
  --without-libpskc --without-gssapi --with-gnutls --without-openssl \
  --with-gnutls-tss2=no --with-external-browser=no \
  --with-vpnc-script=/unavailable/tachiai-no-scripts \
  --with-default-gnutls-priority='NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2:%COMPAT' \
  > "$task_build/configure.log" 2>&1
make -j2 libopenconnect.la > "$task_build/build-pristine.log" 2>&1
# Host dependencies are environment inputs. This pins the OpenConnect source,
# not the host's GnuTLS/libxml2/zlib transitive dependency graph.
read -r -a task_tls_flags <<< "$(pkg-config --cflags --libs gnutls)"
cc -std=c11 -Wall -Wextra -Werror -Wpedantic -O2 -g \
  -I "$task_source" "$task_fixture/lifecycle.c" \
  -L "$task_source/.libs" "-Wl,-rpath,$task_source/.libs" \
  -lopenconnect "${task_tls_flags[@]}" -pthread -o "$task_build/lifecycle"
set +e
"$task_build/lifecycle" --construction-probe
task_status=$?
set -e
[[ "$task_status" = 2 ]] # Reproduce the exact upstream lifecycle defect first.
patch --batch --fuzz=0 -p1 -i "$task_fixture/patches/0001-initialize-command-writer.patch"
make -j2 libopenconnect.la > "$task_build/build-patched.log" 2>&1
"$task_build/lifecycle" --construction-probe
set +e
timeout 10 "$task_build/lifecycle" --cancel-tls-probe
task_status=$?
set -e
[[ "$task_status" = 1 ]] # The unadapted handshake misses the 5-second join.
patch --batch --fuzz=0 -p1 -i "$task_fixture/patches/0002-use-nonblocking-gnutls-handshake.patch"
make -j2 libopenconnect.la > "$task_build/build-nonblocking.log" 2>&1
"$task_build/lifecycle" --cancel-tls-probe
timeout 45 "$task_build/lifecycle"
# Security policy is a distinct restricted library build. Force all objects to
# rebuild: make does not track a changed CPPFLAGS value as a dependency.
for task_patch in 0003-certificate-only-policy 0004-reject-legacy-auth-probe 0005-bound-auth-and-atomic-command-fds 0006-release-xml-session-token 0007-reject-truncated-http-body 0008-bound-http-framing 0009-bound-cstp-headers 0010-cap-http10-read-size 0011-read-sealed-credential-fds; do
  patch --batch --fuzz=0 -p1 -i "$task_fixture/patches/$task_patch.patch"
done
make clean > "$task_build/clean.log" 2>&1
make -j2 CPPFLAGS=-DTACHIAI_CERTIFICATE_ONLY libopenconnect.la > "$task_build/build-restricted.log" 2>&1
for task_file in adapter memory_file adapter_test; do
  cc -std=c11 -Wall -Wextra -Werror -Wpedantic -O2 -g \
    -I "$task_source" -I "$task_fixture" -c "$task_fixture/$task_file.c" \
    -o "$task_build/$task_file.o"
done
cc "$task_build/adapter_test.o" "$task_build/adapter.o" "$task_build/memory_file.o" \
  -L "$task_source/.libs" "-Wl,-rpath,$task_source/.libs" \
  -lopenconnect "${task_tls_flags[@]}" -pthread -o "$task_build/adapter-test"
timeout 60 "$task_build/adapter-test"
# Exercise the actual internal parser in memory, including exact HTTP/1.0,
# cumulative metadata/body bounds and truncated input. No socket/TLS is used.
# Internal objects also reference the optional PKCS11 module detected at configure.
task_parser_packages=(gnutls libxml-2.0 zlib)
task_p11_package="$(sed -n 's/^P11KIT_PC = //p' "$task_source/Makefile")"
if [[ -n "$task_p11_package" ]]; then task_parser_packages+=("$task_p11_package"); fi
read -r -a task_parser_flags <<< "$(pkg-config --cflags --libs "${task_parser_packages[@]}")"
cc -std=c11 -Wall -Wextra -Werror -O2 -g -DTACHIAI_CERTIFICATE_ONLY \
  -isystem "$task_source" -isystem "$task_source/json" \
  "$task_fixture/http_bounds_test.c" "$task_source"/.libs/libopenconnect_la-*.o \
  "$task_source/json/.libs/libopenconnect_la-json.o" \
  "${task_parser_flags[@]}" -lnettle -lhogweed -lgmp -lm -pthread \
  -o "$task_build/http-bounds-test"
"$task_build/http-bounds-test"
printf 'Preserved source, logs and relinkable host fixture: %s\n' "$task_build"
