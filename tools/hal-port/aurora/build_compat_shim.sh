#!/usr/bin/env bash
# Build the AArch64 compatibility shim used by the Aurora plugin-load
# experiment.
#
# The shim provides:
#   - the Xiaomi midebug/DFX symbols Aurora's engine does not export
#   - a "connect" entry point that forwards to the donor plugin's "miviconnect"
#
# Usage:
#   build_compat_shim.sh [OUTPUT.so]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/libshim.so}"

clang --target=aarch64-linux-android21 -fPIC \
  -c "$HERE/compat_shim_connect.c" -o "$HERE/compat_shim_connect.o"
clang --target=aarch64-linux-android21 -fPIC \
  -c "$HERE/compat_vtable.c" -o "$HERE/compat_vtable.o"

clang --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,libshim.so \
  -o "$OUT" "$HERE/compat_shim.S" \
  "$HERE/compat_shim_connect.o" "$HERE/compat_vtable.o"

rm -f "$HERE/compat_shim_connect.o" "$HERE/compat_vtable.o"
echo "built $OUT"
