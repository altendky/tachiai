#!/usr/bin/env bash
set -euo pipefail
# Reuse a reviewed dependency build without changing its sources or prefix.
# OpenConnect itself is always freshly extracted, patched and cross-compiled.
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_ndk="${1:?Pass the existing read-only NDK path}"
task_cache="${2:?Pass the absolute verified source/build cache}"
task_abi="${3:?Pass aarch64 or x86_64}"
task_dependency_build="${4:?Pass the existing normalized full dependency build}"
[[ "$task_ndk" = /* && "$task_cache" = /* && "$task_dependency_build" = /* ]]
case "$task_abi" in aarch64|x86_64) ;; *) exit 1 ;; esac
task_tools="$task_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
task_dependency_prefix="$task_dependency_build/prefix"
[[ -x "$task_tools/$task_abi-linux-android26-clang" ]]
# This refresh is offline. Require every exact original source archive to remain
# available for rebuilding/relinking the dynamically replaceable dependencies.
while IFS=$'\t' read -r task_name _task_url task_sha; do
  printf '%s  %s\n' "$task_sha" "$task_cache/$task_name" | sha256sum --check --status
done < "$task_fixture/sources.tsv"
task_build="$(mktemp -d "$task_cache/android-refresh-$task_abi.XXXXXXXXXX")"
task_prefix="$task_build/prefix"
mkdir -p "$task_prefix/lib" "$task_build/jni-libs" "$task_build/fixture-inputs"
# Retain exact local patches, adapter inputs, notices and recipes used in this
# invocation, rather than relying on a later mutable checkout.
cp -R "$task_fixture/". "$task_build/fixture-inputs/"
task_fixture="$task_build/fixture-inputs"
printf '%s\n' "$task_dependency_build" > "$task_build/dependency-build.txt"
cp "$task_ndk/source.properties" "$task_build/ndk-source.properties"
printf '%s\n' "$task_abi" > "$task_build/ABI"
for task_name in gmp nettle hogweed gnutls xml2 z; do
  task_library="lib$task_name.so"
  # A normalized package must match the exact headers/link libraries used here.
  cmp "$task_dependency_prefix/lib/$task_library" "$task_dependency_build/jni-libs/$task_library"
  cp "$task_dependency_build/jni-libs/$task_library" "$task_build/jni-libs/"
done
tar -xf "$task_cache/openconnect-9.21.tar.gz" -C "$task_build"
task_source="$task_build/openconnect-9.21"
printf 'Fresh Android OpenConnect build: %s\n' "$task_build"
cd "$task_source"
for task_patch in "$task_fixture"/patches/*.patch; do
  patch --batch --fuzz=0 -p1 -i "$task_patch"
done > "$task_build/patch.log" 2>&1
(
  cd "$task_fixture"
  sha256sum patches/*.patch adapter.c adapter.h memory_file.c memory_file.h sources.tsv
) > "$task_build/input-sha256.txt"
(
  cd "$task_fixture"
  sha256sum sources.tsv patches/*.patch adapter.c adapter.h memory_file.c memory_file.h \
    fetch-sources.sh build-android-deps.sh refresh-android-openconnect.sh \
    normalize-android-libs.py verify-android-libs.py
) > "$task_build/native-inputs.sha256"
(
  cd "$task_build"
  sha256sum native-inputs.sha256 ndk-source.properties ABI
) > "$task_build/build-inputs.sha256"
export CC="$task_tools/$task_abi-linux-android26-clang"
export CXX="$task_tools/$task_abi-linux-android26-clang++"
export AR="$task_tools/llvm-ar" RANLIB="$task_tools/llvm-ranlib"
export NM="$task_tools/llvm-nm" STRIP="$task_tools/llvm-strip"
export CFLAGS='-O2 -g -fPIC -MD' CXXFLAGS='-O2 -g -fPIC -MD'
export CPPFLAGS="-DTACHIAI_CERTIFICATE_ONLY -I$task_dependency_prefix/include"
export LDFLAGS="-L$task_dependency_prefix/lib -Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$task_dependency_prefix/lib/pkgconfig" PKG_CONFIG_PATH=''
./configure --host="$task_abi-linux-android" --prefix="$task_prefix" \
  --disable-nls --disable-flask-tests --disable-vhost-net \
  --disable-static --enable-shared --without-lz4 --without-libproxy --without-stoken \
  --without-libpcsclite --without-libpskc --without-gssapi --with-gnutls --without-openssl \
  --with-gnutls-tss2=no --with-external-browser=no \
  --with-vpnc-script=/unavailable/tachiai-no-scripts \
  --with-default-gnutls-priority='NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2:%COMPAT' \
  > configure.log 2>&1
# Change generated link recipes before linking; never rewrite an ELF binary.
sed -i -e 's/^hardcode_libdir_flag_spec=.*/hardcode_libdir_flag_spec=""/' \
  -e 's/^hardcode_into_libs=.*/hardcode_into_libs=no/' libtool
make -j2 libopenconnect.la > build.log 2>&1
make install-libLTLIBRARIES install-includeHEADERS > install.log 2>&1
"$CC" -std=c11 -Wall -Wextra -Werror -Wpedantic -fPIC -shared -O2 -g \
  -I "$task_prefix/include" -I "$task_dependency_prefix/include" -I "$task_fixture" \
  "$task_fixture/adapter.c" "$task_fixture/memory_file.c" \
  -L "$task_prefix/lib" -L "$task_dependency_prefix/lib" -lopenconnect -lgnutls \
  -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,libtachiai_openconnect.so \
  -o "$task_prefix/lib/libtachiai_openconnect.so" > adapter-build.log 2>&1
cp "$task_prefix/lib/libopenconnect.so" "$task_prefix/lib/libtachiai_openconnect.so" "$task_build/jni-libs/"
uv run "$task_fixture/verify-android-libs.py" "$task_build/jni-libs" "$task_ndk" "$task_abi"
(
  cd "$task_build"
  sha256sum build-inputs.sha256
) > "$task_build/native-input-fingerprint.txt"
printf 'Retain dependency build plus this fresh source/object/log/input tree for relinking: %s\n' "$task_build"
