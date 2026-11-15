#!/usr/bin/env bash
# Build the AArch64 compatibility shim used by the Aurora plugin-load experiment.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
clang --target=aarch64-linux-android21 -fPIC -shared -nostdlib \
  -Wl,-soname,libshim.so \
  -o "${1:-$HERE/libshim.so}" "$HERE/compat_shim.S"
