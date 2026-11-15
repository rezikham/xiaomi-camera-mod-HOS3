#!/usr/bin/env python3
"""Redirect the Nezha mileicafilter legend lookup to stylizationType.

The Aurora camera engine does not copy `com.xiaomi.sessionparams.legendMode`
into MiMetadata tag 508, so the stock Nezha plugin reads 0 and disables itself.
Both reads use the same AArch64 immediate:

    add x1, x1, #0x36e      ; "com.xiaomi.sessionparams.legendMode"
    add x1, x1, #0xdd7      ; "com.xiaomi.sessionparams.stylizationType"

The `adrp x1, 0x6000` base is unchanged. After the patch the plugin stores the
stylizationType byte in `this+0x26`, and `initResource` maps it as:

    1 -> M9, 2 -> M3

The module's Legendary bridge writes those bytes into the request when the
app selects M9/M3. See `docs/hal-backport/02-legend-data-path.md`.

Usage:
    patch_plugin.py INPUT OUTPUT
    patch_plugin.py --check INPUT
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ORIGINAL = bytes.fromhex("21b80d91")   # add x1, x1, #0x36e
PATCHED = bytes.fromhex("215c3791")    # add x1, x1, #0xdd7
OFFSETS = (0xC768, 0xD780)             # initialize, isEnabled

# Aurora's libcom.xiaomi.debug.so predates the offline logger symbols that
# Nezha's plugins import. Redirect those imports to the equivalent globals that
# Aurora does export. The replacement is shorter, so the NUL-terminated dynstr
# entry can be rewritten in place.
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
    symbol_states = []
    for old, new in SYMBOLS.items():
        if len(new) > len(old):
            raise SystemExit(f"replacement {new!r} is longer than {old!r}")
        count = data.count(old)
        if count == 0 and new in data:
            symbol_states.append((old, "already patched"))
            continue
        if count != 1:
            raise SystemExit(f"{args.input}: expected one {old!r}, found {count}")
        start = data.index(old)
        data[start:start + len(old)] = new + b"\0" * (len(old) - len(new))
        symbol_states.append((old, "patchable"))

    states = []
    for offset in OFFSETS:
        current = bytes(data[offset:offset + 4])
        if current == PATCHED:
            states.append((offset, "already patched"))
        elif current == ORIGINAL:
            states.append((offset, "patchable"))
            data[offset:offset + 4] = PATCHED
        else:
            raise SystemExit(
                f"{args.input}: unexpected bytes at 0x{offset:x}: {current.hex()}"
            )

    if args.check:
        for old, state in symbol_states:
            print(f"{old.decode()}: {state}")
        for offset, state in states:
            print(f"0x{offset:x}: {state}")
        return 0

    if args.output is None:
        raise SystemExit("OUTPUT is required unless --check is used")
    args.output.write_bytes(data)
    for old, state in symbol_states:
        print(f"{old.decode()}: {state}")
    for offset, state in states:
        print(f"0x{offset:x}: {state}")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
