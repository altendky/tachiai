#!/usr/bin/env bash
set -euo pipefail
# Use the already-built isolated thread-enabled GnuTLS3.8.13 prefix, not a
# runtime substitution underneath an OpenConnect configured for system PKCS11.
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_cache="${1:?Pass the absolute verified source cache}"
task_tls_prefix="${2:?Pass the isolated GnuTLS3.8.13 install prefix}"
[[ "$task_cache" = /* && "$task_tls_prefix" = /* ]]
printf '%s  %s\n' 5b32369467db6e5f317aa1ed12cfcbb81ed00bdbc765450b6bfcbdc300944a58 \
  "$task_cache/openconnect-9.21.tar.gz" | sha256sum --check --status
task_build="$(mktemp -d "$task_cache/openconnect-minimal-host.XXXXXXXXXX")"
tar -xf "$task_cache/openconnect-9.21.tar.gz" -C "$task_build"
task_source="$task_build/openconnect-9.21"
task_prefix="$task_build/prefix"
cd "$task_source"
for task_patch in "$task_fixture"/patches/*.patch; do patch --batch --fuzz=0 -p1 -i "$task_patch"; done
export PKG_CONFIG_PATH="$task_tls_prefix/lib/pkgconfig"
export LD_LIBRARY_PATH="$task_tls_prefix/lib"
./configure --prefix="$task_prefix" --disable-nls --disable-flask-tests --disable-vhost-net \
  --without-lz4 --without-libproxy --without-stoken --without-libpcsclite \
  --without-libpskc --without-gssapi --with-gnutls --without-openssl \
  --with-gnutls-tss2=no --with-external-browser=no \
  --with-vpnc-script=/unavailable/tachiai-no-scripts \
  --with-default-gnutls-priority='NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2:%COMPAT' \
  CPPFLAGS=-DTACHIAI_CERTIFICATE_ONLY > configure.log 2>&1
make -j2 libopenconnect.la > build.log 2>&1
make install-libLTLIBRARIES install-includeHEADERS > install.log 2>&1
read -r -a task_flags <<< "$(pkg-config --cflags --libs gnutls)"
cc -std=c11 -Wall -Wextra -Werror -Wpedantic -fPIC -shared -O2 -g \
  -I "$task_prefix/include" "$task_fixture/adapter.c" "$task_fixture/memory_file.c" \
  -L "$task_prefix/lib" "-Wl,-rpath,$task_prefix/lib" "-Wl,-rpath,$task_tls_prefix/lib" \
  -lopenconnect "${task_flags[@]}" -pthread -Wl,--no-undefined \
  -o "$task_prefix/lib/libtachiai_openconnect.so"
printf 'Pinned minimal host C ABI prefix: %s\n' "$task_prefix"
