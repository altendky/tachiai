#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Re-link completed disposable builds with replaceable Android .so names.

Only generated build files/artifacts in the supplied cache are modified.
All executable code stays dynamically separated; no ELF binary rewriting.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

build, ndk, abi = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
if abi not in {"aarch64", "x86_64"} or not (build / "prefix/lib/libtachiai_openconnect.so").is_file():
    raise SystemExit("Require a completed native spike and its ABI")
tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
cc = str(tools / f"{abi}-linux-android26-clang")
prefix = build / "prefix"
env = dict(os.environ, PKG_CONFIG_LIBDIR=str(prefix / "lib/pkgconfig"), PKG_CONFIG_PATH="")
log = (build / "normalize.log").open("w")

def run(args, cwd):
    subprocess.run(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

def remove(path):
    if path.exists() or path.is_symlink():
        path.unlink()

nettle = build / "nettle-3.10.2"
for name in ("libnettle.so", "libhogweed.so"):
    remove(nettle / name)
run(["make", "-j2", "libnettle.so", "libhogweed.so",
     f"LIBNETTLE_LINK={cc} -shared -L{prefix}/lib -Wl,-z,max-page-size=16384 -Wl,-soname,libnettle.so",
     f"LIBHOGWEED_LINK={cc} -shared -L{prefix}/lib -Wl,-z,max-page-size=16384 -Wl,-soname,libhogweed.so"], nettle)
run(["make", "install"], nettle)

for package, library, location in (("gnutls-3.8.13", "libgnutls", "lib"),
                                   ("libxml2-2.15.4", "libxml2", "."),
                                   ("openconnect-9.21", "libopenconnect", ".")):
    source = build / package
    libtool = source / "libtool"
    data = libtool.read_text()
    data = re.sub(r"^hardcode_libdir_flag_spec=.*$", 'hardcode_libdir_flag_spec=""', data, flags=re.M)
    data = re.sub(r"^hardcode_into_libs=.*$", "hardcode_into_libs=no", data, flags=re.M)
    libtool.write_text(data)
    directory = source / location
    remove(directory / f"{library}.la")
    remove(directory / f".libs/{library}.so")
    run(["make", "-j2", f"{library}.la"], directory)
    run(["make", "install-libLTLIBRARIES"], directory)

zlib = build / "zlib-1.3.2"
env.update(CC=cc, AR=str(tools / "llvm-ar"), RANLIB=str(tools / "llvm-ranlib"),
           CFLAGS="-O2 -g -fPIC -MD", LDFLAGS=f"-L{prefix}/lib -Wl,-z,max-page-size=16384",
           LDSHARED=f"{cc} -shared -Wl,-soname,libz.so")
# zlib's configure probes its complete version script against a tiny test
# object; current LLD rejects undefined script assignments and silently falls
# back to static-only. Select the actual Android shared linker explicitly.
run(["./configure", f"--prefix={prefix}", "--shared"], zlib)
zlib_make = (zlib / "Makefile").read_text()
zlib_target = re.search(r"^SHAREDLIBV=(.*)$", zlib_make, flags=re.M).group(1).strip()
if not zlib_target:
    raise SystemExit("zlib shared configuration refused; no static substitution")
remove(zlib / zlib_target)
run(["make", zlib_target, f"LDSHARED={cc} -shared -Wl,-z,max-page-size=16384 -Wl,-soname,libz.so"], zlib)
run(["make", "install"], zlib)
# OpenConnect must relink again after zlib's final SONAME is selected.
source = build / "openconnect-9.21"
remove(source / "libopenconnect.la")
remove(source / ".libs/libopenconnect.so")
run(["make", "-j2", "libopenconnect.la"], source)
run(["make", "install-libLTLIBRARIES"], source)
fixture = Path(__file__).resolve().parent
run([cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wpedantic", "-fPIC", "-shared", "-O2", "-g",
     "-I", str(prefix / "include"), "-I", str(fixture), str(fixture / "adapter.c"),
     str(fixture / "memory_file.c"), "-L", str(prefix / "lib"), "-lopenconnect", "-lgnutls",
     "-Wl,-z,max-page-size=16384", "-Wl,--no-undefined", "-Wl,-soname,libtachiai_openconnect.so",
     "-o", str(prefix / "lib/libtachiai_openconnect.so")], source)
names = {f"lib{name}.so" for name in ("gmp", "nettle", "hogweed", "gnutls", "xml2", "z", "openconnect", "tachiai_openconnect")}
package_dir = build / "jni-libs"
package_dir.mkdir(exist_ok=True)
for name in sorted(names):
    source = prefix / "lib" / name
    target = package_dir / name
    shutil.copyfile(source, target)
log.close()
subprocess.run([sys.executable, str(fixture / "verify-android-libs.py"), str(package_dir), str(ndk), abi], check=True)
