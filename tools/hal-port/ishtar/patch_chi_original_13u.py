#!/usr/bin/env python3
"""
Patch Xiaomi 13 Ultra (ishtar) /odm/lib64/hw/com.qti.chi.override.so so
stylizationType == 2 (Leica Original in Photo mode) no longer triggers a UBSan
abort() in ExtensionModule::GetbokehFallbackLuxThreshold.

Root cause in stock ishtar com.qti.chi.override.so (OS3.0.308.0.WMACNXM):
  0x44e400: and w9, w2, #0xff
  0x44e404: cmp w9, #0x2        ; off-by-one! allows style == 2 into [2][8] array
  0x44e408: b.hi 0x44e440
  0x44e410: cmp w8, #0x8        ; off-by-one! allows idx == 8 into [2][8] array
  0x44e414: b.hi 0x44e440
  0x44e41c: cmp w10, #0x1       ; UBSan trap if style > 1 -> abort()
  0x44e420: b.hi 0x44e538

Changing 0x44e404 from `cmp w9, #0x2` to `cmp w9, #0x1` and 0x44e410 from
`cmp w8, #0x8` to `cmp w8, #0x7` returns 0.0f cleanly instead of aborting.
"""

import struct
import sys
from pathlib import Path

PATCHES = [
    # (offset, expected_insn, patched_insn, description)
    (0x44E404, 0x7100093F, 0x7100053F, "cmp w9, #0x2 -> cmp w9, #0x1"),
    (0x44E410, 0x7100211F, 0x71001D1F, "cmp w8, #0x8 -> cmp w8, #0x7"),
]


def main() -> int:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input_chi_override.so> <output_chi_override.so>")
        return 1

    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    data = bytearray(src.read_bytes())

    for off, expected, replacement, desc in PATCHES:
        actual = struct.unpack_from("<I", data, off)[0]
        if actual != expected:
            raise RuntimeError(
                f"Mismatch at 0x{off:x}: expected 0x{expected:08x}, got 0x{actual:08x}"
            )
        struct.pack_into("<I", data, off, replacement)
        print(f"Patched 0x{off:x}: 0x{expected:08x} -> 0x{replacement:08x} ({desc})")

    dst.write_bytes(data)
    print(f"Wrote {dst} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
