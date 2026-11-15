#!/usr/bin/env python3
"""Patch donor-plugin field offsets that differ from Aurora's mivifwk.

The Nezha/Madrid framework structs have extra leading fields. Two examples the
donor plugin relies on:

    Nezha MiaParams.metadata      +0x18     Aurora MiaParams.metadata  +0x00
    Nezha CreateInfo.metadata     +0x48     Aurora CreateInfo has no
                                            metadata pointer (it is delivered
                                            through MiaParams instead)

``MiLeicaFilterPlugin::isEnabled(MiaParams)`` performs two identical
``MiMetadata::find()`` lookups through ``[MiaParams+0x18]``:

    0xd74c: ldr x0, [x1, #0x18]     ->  ldr x0, [x1]
    0xd778: ldr x0, [x21, #0x18]    ->  ldr x0, [x21]

Both are rewritten to Aurora's +0x00. The metadata key is already
``com.xiaomi.sessionparams.stylizationType`` (see patch_plugin.py).

Usage:
    patch_plugin_framework_abi.py PLUGIN
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# (offset, expected u32, replacement u32)
PATCHES = [
    (0xD74C, 0xF9400C20, 0xF9400020),  # ldr x0, [x1, #0x18]  -> [x1]
    (0xD778, 0xF9400EA0, 0xF94002A0),  # ldr x0, [x21, #0x18] -> [x21]
    # initialize(): Aurora's CreateInfo has no metadata pointer, so skip the
    # two MiMetadata::find() reads (they only feed debug state at +0x25/+0x26).
    (0xC734, 0xF9402680, 0x14000017),  # ldr x0, [x20, #0x48] -> b 0xc790
]


def u32(data: bytearray, off: int) -> int:
    return int.from_bytes(data[off : off + 4], "little")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("plugin", type=Path)
    args = ap.parse_args()

    data = bytearray(args.plugin.read_bytes())
    changed = 0
    for off, expected, replacement in PATCHES:
        actual = u32(data, off)
        if actual == replacement:
            print(f"{off:#x}: already patched")
            continue
        if actual != expected:
            print(
                f"{off:#x}: unexpected instruction {actual:#010x}, "
                f"expected {expected:#010x}",
                file=sys.stderr,
            )
            return 1
        data[off : off + 4] = replacement.to_bytes(4, "little")
        print(f"{off:#x}: {expected:#010x} -> {replacement:#010x}")
        changed += 1

    args.plugin.write_bytes(data)
    print(f"patched {changed} site(s) in {args.plugin}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
