#!/usr/bin/env bash
set -euo pipefail

# Native dependencies only. Does not invoke Gradle, gomobile, SDK manager or ADB.
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_bridge="$task_root/apps/android/routebridge"
: "${ANDROID_NDK_ROOT:?Use the existing read-only Android NDK}"
task_build="${TACHIAI_OPENVPN_BUILD_ROOT:-$task_bridge/build/openvpn-native}"
mkdir -p "$task_build"
task_build="$(cd "$task_build" && pwd)"
task_ndk="$ANDROID_NDK_ROOT"
task_jobs="${TACHIAI_OPENVPN_BUILD_JOBS:-2}"
test "$(sed -n 's/^Pkg.Revision = //p' "$task_ndk/source.properties")" = 30.0.16248370
task_tools="$task_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
task_toolchain="$task_ndk/build/cmake/android.toolchain.cmake"
mkdir -p "$task_build/downloads" "$task_build/sources" "$task_build/source-payload"
mkdir -p "$task_build/tmp"
export TMPDIR="$task_build/tmp"

fetch_source() {
  local task_name="$1" task_repository="$2" task_commit="$3" task_hash="$4"
  local task_archive="$task_build/downloads/$task_name.tar.gz"
  if ! test -f "$task_archive"; then
    curl --fail --location --silent --show-error \
      "https://codeload.github.com/$task_repository/tar.gz/$task_commit" --output "$task_archive.part"
    mv "$task_archive.part" "$task_archive"
  fi
  test "$(sha256sum "$task_archive" | cut -d ' ' -f 1)" = "$task_hash"
  if ! test -d "$task_build/sources/$task_name"; then
    mkdir -p "$task_build/sources/$task_name"
    tar -xzf "$task_archive" --strip-components=1 -C "$task_build/sources/$task_name"
  fi
  cp "$task_archive" "$task_build/source-payload/$task_name.tar.gz"
}

fetch_source openvpn3 OpenVPN/openvpn3 2f74c607f56294e39c9fab253f6dd87a2ed6e9ab 4e01b6047f1be3eedfc2592576f27333574d8ff974cc810062a42b5f15f2b084
fetch_source asio chriskohlhoff/asio 231cb29bab30f82712fcd54faaea42424cc6e710 5def09efbd4be199dd6ddca53a2c99b9eef696f6b430910d896594b04ff59108
fetch_source openssl openssl/openssl 67b5686b4419b4cb8caa502711c41815f5279751 f7c4a5e19fa02056e2bff4240bf474a3935207eb8fce6ee56175350acc6e2c32
fetch_source fmt fmtlib/fmt e57ca2e3685b160617d3d95fcd9e789c4e06ca88 2d18d7b1c393791a180c8321cedd702093b5527625be955178bef8af2b1cf6db
fetch_source lz4 lz4/lz4 ebb370ca83af193212df4dcbadcc5d87bc0de2f0 eb1a93e934d4fd29df6e2061ba0bf447568561764d758f4a5662c0e29370ffa9

# Changing a native input or the fixed toolchain/build configuration invalidates
# both ABI outputs. An explicitly supplied validation cache predating this stamp
# is reusable only when its recorded native sources match exactly.
task_configuration='NDK30.0.16248370 API26 PIC c++_static OpenSSL:no-shared,no-tests,no-apps,no-legacy,no-engine,no-dso,no-module,no-autoload-config fmt:static lz4:library-static native:release 16KiB v1'
task_fingerprint="$(
  printf '%s\n' "$task_configuration"
  sha256sum "$task_build/downloads/"*.tar.gz | awk '{print $1}'
  sha256sum "$task_bridge/native/adapter.cpp" "$task_bridge/native/adapter.h" \
    "$task_bridge/native/linkcheck.cpp" "$task_bridge/native/CMakeLists.txt" | awk '{print $1}'
)"
task_reuse=false
if test -f "$task_build/build-inputs.txt"; then
  if test "$(< "$task_build/build-inputs.txt")" = "$task_fingerprint"; then
    task_reuse=true
  fi
elif test -n "${TACHIAI_OPENVPN_BUILD_ROOT:-}"; then
  task_reuse=true
  for task_input in adapter.cpp adapter.h linkcheck.cpp CMakeLists.txt; do
    if ! cmp -s "$task_bridge/native/$task_input" "$task_build/source-payload/$task_input"; then
      task_reuse=false
    fi
  done
fi
for task_abi in arm64-v8a x86_64; do
  for task_library in tachiai_openvpn ssl crypto fmt lz4; do
    if ! test -f "$task_build/$task_abi/prefix/lib/lib$task_library.a"; then
      task_reuse=false
    fi
  done
  if ! test -f "$task_build/$task_abi/native/libtachiai_openvpn_linkcheck.so"; then
    task_reuse=false
  fi
done

for task_abi in arm64-v8a x86_64; do
  task_prefix="$task_build/$task_abi/prefix"
  if ! "$task_reuse"; then
    mkdir -p "$task_prefix" "$task_build/$task_abi/openssl"
    case "$task_abi" in
      arm64-v8a) task_ssl_platform=android-arm64 ;;
      x86_64) task_ssl_platform=android-x86_64 ;;
    esac
    (
      cd "$task_build/$task_abi/openssl"
      export PATH="$task_tools:$PATH"
      perl "$task_build/sources/openssl/Configure" "$task_ssl_platform" \
        -D__ANDROID_API__=26 -fPIC no-shared no-tests no-apps no-legacy \
        no-engine no-dso no-module no-autoload-config --prefix="$task_prefix" --libdir=lib
      make -j "$task_jobs"
      make install_sw
    )
    task_cmake_common=(
      -DCMAKE_TOOLCHAIN_FILE="$task_toolchain" -DANDROID_ABI="$task_abi"
      -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
      -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
      -DCMAKE_INSTALL_PREFIX="$task_prefix"
    )
    cmake -S "$task_build/sources/fmt" -B "$task_build/$task_abi/fmt" -G Ninja \
      "${task_cmake_common[@]}" -DFMT_TEST=OFF -DFMT_DOC=OFF -DFMT_INSTALL=ON -DBUILD_SHARED_LIBS=OFF
    cmake --build "$task_build/$task_abi/fmt" --parallel "$task_jobs"
    cmake --install "$task_build/$task_abi/fmt"
    cmake -S "$task_build/sources/lz4/build/cmake" -B "$task_build/$task_abi/lz4" -G Ninja \
      "${task_cmake_common[@]}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DLZ4_BUILD_CLI=OFF \
      -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DLZ4_POSITION_INDEPENDENT_LIB=ON
    cmake --build "$task_build/$task_abi/lz4" --parallel "$task_jobs"
    cmake --install "$task_build/$task_abi/lz4"
    task_native_cache="$task_build/$task_abi/native"
    if test -f "$task_native_cache/CMakeCache.txt"; then
      task_cached_source="$(sed -n 's/^CMAKE_HOME_DIRECTORY:INTERNAL=//p' "$task_native_cache/CMakeCache.txt")"
      if test "$task_cached_source" != "$task_bridge/native"; then
        # CMake refuses to reuse a build directory from a different worktree.
        # Only this ABI's generated adapter build is discarded; dependencies
        # and pinned source archives remain available in the shared task cache.
        rm -rf -- "$task_native_cache"
      fi
    fi
    cmake -S "$task_bridge/native" -B "$task_build/$task_abi/native" -G Ninja \
      "${task_cmake_common[@]}" -DOPENVPN3_SOURCE_DIR="$task_build/sources/openvpn3" \
      -DASIO_SOURCE_DIR="$task_build/sources/asio" -DTACHIAI_OPENVPN_DEPENDENCY_PREFIX="$task_prefix"
    cmake --build "$task_build/$task_abi/native" --parallel "$task_jobs"
    cp "$task_build/$task_abi/native/libtachiai_openvpn.a" "$task_prefix/lib/"
  else
    printf 'Reusing verified OpenVPN native inputs for %s\n' "$task_abi"
  fi
  # Fully link all native dependencies to prove ABI/no-undefined/16-KiB layout.
  task_shared="$task_build/$task_abi/native/libtachiai_openvpn_linkcheck.so"
  case "$task_abi" in
    arm64-v8a) task_machine=AArch64 ;;
    x86_64) task_machine='Advanced Micro Devices X86-64' ;;
  esac
  "$task_tools/llvm-readelf" -h "$task_shared" | \
    awk -v expected="$task_machine" '/Machine:/{sub(/^.*Machine: +/, ""); if ($0 != expected) exit 1; found=1} END {if (!found) exit 1}'
  "$task_tools/llvm-readelf" -l "$task_shared" | \
    awk '/LOAD/{if ($NF != "0x4000" && $NF != "0x10000") exit 1; found=1} END {if (!found) exit 1}'
  "$task_tools/llvm-readelf" -d "$task_shared" | \
    awk '/NEEDED/{if ($NF != "[libc.so]" && $NF != "[libdl.so]" && $NF != "[libm.so]") exit 1}'
  sha256sum "$task_prefix/lib/libtachiai_openvpn.a" "$task_shared"
done
printf '%s\n' "$task_fingerprint" > "$task_build/build-inputs.txt"

# Exact public sources plus our build recipe/notices accompany every later
# distributable native artifact. Parent packaging embeds notices in the APK.
cp "$0" "$task_build/source-payload/"
cp "$task_bridge/native/"*.cpp "$task_bridge/native/"*.h "$task_bridge/native/CMakeLists.txt" \
  "$task_bridge/native/README.md" "$task_build/source-payload/"
cp "$task_bridge/licenses/"*.txt "$task_bridge/THIRD-PARTY-NOTICES.md" "$task_build/source-payload/"
cp "$task_bridge/openvpn_validation.go" "$task_bridge/route_preparation.go" "$task_build/source-payload/"
# A source companion is not a package inside the application's Go module.
# Keep an explicit nested module boundary when the default cache is in build/.
cp "$task_bridge/go.mod" "$task_bridge/go.sum" "$task_build/source-payload/"
cp "$task_build/build-inputs.txt" "$task_build/source-payload/"
(cd "$task_build/source-payload" && sha256sum -- * > ../source-payload.sha256)
