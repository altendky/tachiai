#!/usr/bin/env bash
set -euo pipefail
# Native-only, disposable dependency spike. Never installs into the SDK/host.
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_ndk="${1:?Pass the existing read-only NDK path}"
task_cache="${2:?Pass an absolute disposable source/build cache}"
task_abi="${3:?Pass aarch64 or x86_64}"
[[ "$task_ndk" = /* && "$task_cache" = /* ]]
case "$task_abi" in aarch64|x86_64) ;; *) exit 1 ;; esac
task_tools="$task_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
task_build="$(mktemp -d "$task_cache/android-$task_abi.XXXXXXXXXX")"
task_prefix="$task_build/prefix"
mkdir -p "$task_prefix"
"$task_fixture/fetch-sources.sh" "$task_cache"
while IFS=$'\t' read -r task_name _task_url _task_sha; do
  tar -xf "$task_cache/$task_name" -C "$task_build"
done < "$task_fixture/sources.tsv"
export CC="$task_tools/$task_abi-linux-android26-clang"
export CXX="$task_tools/$task_abi-linux-android26-clang++"
export AR="$task_tools/llvm-ar" RANLIB="$task_tools/llvm-ranlib"
export NM="$task_tools/llvm-nm" STRIP="$task_tools/llvm-strip"
export CFLAGS='-O2 -g -fPIC -MD' CXXFLAGS='-O2 -g -fPIC -MD'
export CPPFLAGS="-I$task_prefix/include"
export LDFLAGS="-L$task_prefix/lib -Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$task_prefix/lib/pkgconfig" PKG_CONFIG_PATH=''
task_host="$task_abi-linux-android"
task_configure() {
  ./configure --host="$task_host" --prefix="$task_prefix" "$@" > configure.log 2>&1
}
task_make_install() {
  make -j2 > build.log 2>&1
  make install > install.log 2>&1
}
printf 'Native dependency build: %s\n' "$task_build"
cd "$task_build/gmp-6.3.0"
task_configure --disable-assembly --disable-cxx --disable-static --enable-shared --with-pic
task_make_install
cd "$task_build/nettle-3.10.2"
task_configure --disable-assembler --disable-fat --disable-openssl --disable-documentation \
  --disable-static --with-include-path="$task_prefix/include" --with-lib-path="$task_prefix/lib"
task_make_install
cd "$task_build/gnutls-3.8.13"
task_configure --disable-static --enable-shared --disable-doc --disable-tools \
  --disable-tests --disable-cxx --disable-nls --disable-libdane \
  --disable-hardware-acceleration --disable-padlock --enable-threads=posix \
  --with-included-libtasn1 --with-included-unistring --without-p11-kit \
  --without-tpm --without-tpm2 --without-idn --without-zlib --without-brotli --without-zstd \
  --with-system-priority-file= --with-default-trust-store-file= --with-default-trust-store-dir=
task_make_install
cd "$task_build/libxml2-2.15.4"
task_configure --disable-static --enable-shared --without-python --without-iconv \
  --without-icu --without-zlib --without-http --without-modules
task_make_install
cd "$task_build/zlib-1.3.2"
./configure --prefix="$task_prefix" --shared > configure.log 2>&1
task_make_install
cd "$task_build/openconnect-9.21"
for task_patch in "$task_fixture"/patches/*.patch; do
  patch --batch --fuzz=0 -p1 -i "$task_patch"
done
export CPPFLAGS="-DTACHIAI_CERTIFICATE_ONLY -I$task_prefix/include"
task_configure --disable-nls --disable-flask-tests --disable-vhost-net \
  --disable-static --enable-shared --without-lz4 --without-libproxy --without-stoken \
  --without-libpcsclite --without-libpskc --without-gssapi --with-gnutls --without-openssl \
  --with-gnutls-tss2=no --with-external-browser=no \
  --with-vpnc-script=/unavailable/tachiai-no-scripts \
  --with-default-gnutls-priority='NORMAL:-VERS-ALL:+VERS-TLS1.3:+VERS-TLS1.2:%COMPAT'
make -j2 libopenconnect.la > build.log 2>&1
# Install only the library and public header; no VPN executable or helper.
make install-libLTLIBRARIES install-includeHEADERS > install.log 2>&1
"$CC" -std=c11 -Wall -Wextra -Werror -Wpedantic -fPIC -shared -O2 -g \
  -I "$task_prefix/include" -I "$task_fixture" "$task_fixture/adapter.c" \
  "$task_fixture/memory_file.c" -L "$task_prefix/lib" -lopenconnect -lgnutls \
  -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,libtachiai_openconnect.so \
  -o "$task_prefix/lib/libtachiai_openconnect.so"
for task_library in "$task_prefix/lib/"*.so*; do
  [[ -L "$task_library" ]] && continue
  "$task_tools/llvm-readelf" --program-headers --dynamic --dyn-syms --wide "$task_library" > "$task_library.elf.txt"
  "$task_tools/llvm-readelf" --program-headers --wide "$task_library" | \
    awk '/LOAD/ { seen=1; if ($NF != "0x4000") exit 1 } END { if (!seen) exit 1 }'
done
printf 'PASS: full API26 %s native shared closure linked, with 16KiB LOAD alignment: %s\n' "$task_abi" "$task_build"
printf 'Runtime/JNI/APK packaging and ELF dependency replacement are separate gates.\n'
