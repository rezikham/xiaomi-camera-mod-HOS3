#!/usr/bin/env python3
"""Redirect Nezha plugin imports that Aurora's libcom.xiaomi.debug.so lacks.

Aurora's debug library predates the offline logger globals:

    midebug::gMiCamOfflineLogLevel
    midebug::gMiCamOfflineLogGroup

Nezha's `mileicafilter` and `legendST` import them, so dlopen stops with
``cannot locate symbol``. The equivalent Aurora globals are
``midebug::gMiCamLogLevel`` and ``midebug::gMiCamLogGroup``. The replacement
mangled names are shorter, so the NUL-terminated `.dynstr` entries can be
rewritten in place without moving anything.

Usage:
    patch_debug_symbols.py INPUT OUTPUT
    patch_debug_symbols.py --check INPUT
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SYMBOLS = {
    b"_ZN7midebug21gMiCamOfflineLogLevelE": b"_ZN7midebug14gMiCamLogLevelE",
    b"_ZN7midebug21gMiCamOfflineLogGroupE": b"_ZN7midebug14gMiCamLogGroupE",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    data = bytearray(args.input.read_bytes())
    states = []
    for old, new in SYMBOLS.items():
        if len(new) > len(old):
            raise SystemExit(f"replacement {new!r} is longer than {old!r}")
        count = data.count(old)
        if count == 0 and new in data:
            states.append((old, "already patched"))
            continue
        if count != 1:
            raise SystemExit(f"{args.input}: expected one {old!r}, found {count}")
        start = data.index(old)
        data[start:start + len(old)] = new + b"\0" * (len(old) - len(new))
        states.append((old, "patchable"))

    for old, state in states:
        print(f"{old.decode()}: {state}")

    if args.check:
        return 0
    if args.output is None:
        raise SystemExit("OUTPUT is required unless --check is used")
    args.output.write_bytes(data)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
