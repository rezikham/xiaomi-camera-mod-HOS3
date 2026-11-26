#!/usr/bin/env python3
"""Bridge 14 Ultra com.xiaomi.plugin.filter.so to 18 Pro Max libMiPhotoFilter.so.

Why this is needed:
  - Camera 6.7 (14U) and Camera 6.8 (18PM) ship bit-for-bit identical
    libMiFilterSDK.so (md5 d23f8e8d3e2b1d93f681d8325184fd9e), so the Palette
    and Parameters editor (Saturation, Tone, Soft light, Cold/Warm, Cyan/Magenta,
    Noise, Sharpness, Vignette) renders in live preview on 14U via
    @StyleAdjustEffect;Algos=...
  - On capture, com.xiaomi.plugin.filter.so hands that filterScript straight to
    /odm/lib64/libMiPhotoFilter.so. Stock 14U libMiPhotoFilter.so (1,083,576 B)
    lacks StyleAdjustEffect completely (and uses the older CvStyleEffect without
    RatioSat), so all Palette/Parameters edits are silently dropped on capture.
  - 18PM libMiPhotoFilter.so (1,350,800 B) links only standard Android NDK libs
    (liblog, libGLESv3, libEGL, libnativewindow, libz, libm, libdl, libc) and
    implements full 18PM StyleAdjustEffect + RatioSat CvStyleEffect.
  - Its VideoFilter vtable inserts updateDynamicWaterMark at +0x50, shifting
    setFilterScript (+0x50 -> +0x58) and Process (+0x60 -> +0x68) by +8 bytes.
  - Stock com.xiaomi.plugin.watermark.so stays on stock /odm/lib64/libMiPhotoFilter.so;
    only com.xiaomi.plugin.filter.so is redirected to libMiPhotoFlt18p.so (exact
    19-char .dynstr match) with its 4 vtable-load instructions updated by +8.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path

OLD_SONAME = b"libMiPhotoFilter.so\x00"
NEW_SONAME = b"libMiPhotoFlt18p.so\x00"
assert len(OLD_SONAME) == len(NEW_SONAME)

# Exact file offsets (== vaddrs in first RX LOAD segment) in 14U com.xiaomi.plugin.filter.so
VTABLE_PATCHES = [
    (0xB138, 0xF9402908, 0xF9402D08, "candyProcess: setFilterScript (+0x50 -> +0x58)"),
    (0xB5B4, 0xF9403108, 0xF9403508, "candyProcess: Process (+0x60 -> +0x68)"),
    (0xC8E4, 0xF9402908, 0xF9402D08, "processRequest(tilt): setFilterScript (+0x50 -> +0x58)"),
    (0xC918, 0xF9403108, 0xF9403508, "processRequest(tilt): Process (+0x60 -> +0x68)"),
]


def patch_filter_plugin(src: Path, dst: Path) -> str:
    data = bytearray(src.read_bytes())
    for off, old_insn, new_insn, desc in VTABLE_PATCHES:
        cur = struct.unpack_from("<I", data, off)[0]
        if cur != old_insn:
            raise ValueError(
                f"Unexpected instruction at 0x{off:x} ({desc}): got 0x{cur:08x}, expected 0x{old_insn:08x}"
            )
        struct.pack_into("<I", data, off, new_insn)
        print(f"  patched 0x{off:05x}: 0x{old_insn:08x} -> 0x{new_insn:08x} ({desc})")

    cnt = data.count(OLD_SONAME)
    if cnt != 1:
        raise ValueError(f"Expected 1 occurrence of {OLD_SONAME!r} in {src}, found {cnt}")
    idx = data.index(OLD_SONAME)
    data[idx : idx + len(NEW_SONAME)] = NEW_SONAME
    print(f"  patched DT_NEEDED @ 0x{idx:x}: {OLD_SONAME[:-1].decode()} -> {NEW_SONAME[:-1].decode()}")

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(bytes(data))
    return hashlib.sha256(bytes(data)).hexdigest()


def patch_lib_soname(src: Path, dst: Path) -> str:
    data = bytearray(src.read_bytes())
    cnt = data.count(OLD_SONAME)
    if cnt != 1:
        raise ValueError(f"Expected 1 occurrence of {OLD_SONAME!r} in {src}, found {cnt}")
    idx = data.index(OLD_SONAME)
    data[idx : idx + len(NEW_SONAME)] = NEW_SONAME
    print(f"  patched DT_SONAME @ 0x{idx:x}: {OLD_SONAME[:-1].decode()} -> {NEW_SONAME[:-1].decode()}")

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(bytes(data))
    return hashlib.sha256(bytes(data)).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("filter_plugin_14u", type=Path, help="Stock 14U com.xiaomi.plugin.filter.so")
    parser.add_argument("miphoto_18pm", type=Path, help="18PM libMiPhotoFilter.so")
    parser.add_argument("out_dir", type=Path, help="Output directory for patched binaries")
    args = parser.parse_args()

    out_plugin = args.out_dir / "com.xiaomi.plugin.filter.so"
    out_lib = args.out_dir / "libMiPhotoFlt18p.so"

    print(f"Patching {args.filter_plugin_14u} -> {out_plugin}")
    sha_p = patch_filter_plugin(args.filter_plugin_14u, out_plugin)
    print(f"  sha256={sha_p[:16]}... ({out_plugin.stat().st_size} bytes)")

    print(f"Patching {args.miphoto_18pm} -> {out_lib}")
    sha_l = patch_lib_soname(args.miphoto_18pm, out_lib)
    print(f"  sha256={sha_l[:16]}... ({out_lib.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
