#!/usr/bin/env python3
"""Extract the authentic 18 Pro Max M10R (CMOS) LUT and trimmed filter bin.

On the 18 Pro Max (madrid), leica_filter_param_m10r_snapshot.bin stores 108/108
neutral identity LUTs because snapshot delegates color transfer to the QNN HTP
V85 styletrans_*_m10r.minn bilateral-grid UNet. However, real-time viewfinder
preview cannot run the 40 MB UNet per frame, so Xiaomi bakes the official
18 Pro Max Leica M10-R (CMOS) 17^3 3D LUT into all 108 slots of
leica_filter_param_m10r_preview.bin (mean diff 10.70/255 vs identity, 17.79/255
vs 18PM M9 preview).

This script:
  1. Extracts the genuine 17^3 M10R LUT from leica_filter_param_m10r_preview.bin
     and trilinearly upsamples it into the camera's 512x512 (64^3, 8x8 tiles)
     strip-LUT PNG format (M10R_Cmos.png / lut_m10r.png).
  2. Optionally writes a libm9base/B12-compatible trimmed filter bin
     (1,598,108 -> 1,597,668 bytes) by converting the 55 trailing 40-byte
     records [int32(9), float32[9]] to the 32-byte format [float32[8]].

Usage:
    python3 make_m10r_lut.py <leica_filter_param_m10r_preview.bin> <out_lut.png> [--trim-bin <out.bin>]
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path

import numpy as np
from PIL import Image

HEADER_SIZE = 4096
LUT_COUNT = 108
LUT17_BYTES = 17 * 17 * 17 * 3  # 14,739 bytes
TAIL_RECORDS = 55
TAIL_REC_SRC = 40  # int32(9) + float32[9]
TAIL_REC_DST = 32  # float32[8]


def upsample_17_to_64(lut17: np.ndarray) -> np.ndarray:
    """Trilinearly upsample a (17, 17, 17, 3) [b, g, r] LUT to (64, 64, 64, 3)."""
    coords = np.linspace(0.0, 16.0, 64, dtype=np.float32)
    i0 = np.floor(coords).astype(np.int32)
    i1 = np.minimum(16, i0 + 1)
    t = coords - i0

    c0 = lut17[:, :, i0, :] * (1.0 - t)[None, None, :, None] + lut17[:, :, i1, :] * t[None, None, :, None]
    c1 = c0[:, i0, :, :] * (1.0 - t)[None, :, None, None] + c0[:, i1, :, :] * t[None, :, None, None]
    c2 = c1[i0, :, :, :] * (1.0 - t)[:, None, None, None] + c1[i1, :, :, :] * t[:, None, None, None]
    return np.clip(np.rint(c2), 0, 255).astype(np.uint8)


def bake_512_png(lut64: np.ndarray, out_png: Path) -> str:
    """Write (64, 64, 64, 3) [b, g, r] LUT to 512x512 8x8-tiled PNG and return MD5."""
    atlas = np.zeros((512, 512, 3), dtype=np.uint8)
    for b in range(64):
        tx = (b & 7) * 64
        ty = (b >> 3) * 64
        atlas[ty : ty + 64, tx : tx + 64] = lut64[b]
    out_png.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(atlas, mode="RGB").save(out_png)
    return hashlib.md5(out_png.read_bytes()).hexdigest()


def trim_filter_bin(src_bytes: bytes, out_bin: Path) -> str:
    """Strip 8 bytes per trailing record (440 bytes total) for libm9base/B12 compatibility.

    Supports both M10R (108 LUTs -> 1,598,108 B) and M9 (114 LUTs -> 1,686,542 B) 18PM bins.
    """
    payload = len(src_bytes) - HEADER_SIZE - TAIL_RECORDS * TAIL_REC_SRC
    if payload <= 0 or payload % LUT17_BYTES != 0:
        raise ValueError(f"Unexpected bin length {len(src_bytes)} (not a 17^3 LUT + 55x40 tail bin)")
    tail_off = HEADER_SIZE + payload
    out = bytearray(src_bytes[:tail_off])
    for i in range(TAIL_RECORDS):
        rec_off = tail_off + i * TAIL_REC_SRC
        count = struct.unpack_from("<I", src_bytes, rec_off)[0]
        if count != 9:
            raise ValueError(f"Unexpected tail record[{i}] count={count} (expected 9)")
        out.extend(src_bytes[rec_off + 4 : rec_off + 4 + TAIL_REC_DST])
    out_bin.parent.mkdir(parents=True, exist_ok=True)
    out_bin.write_bytes(bytes(out))
    return hashlib.sha256(bytes(out)).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("preview_bin", type=Path, help="Path to 18PM leica_filter_param_m10r_preview.bin")
    parser.add_argument("out_png", type=Path, help="Output 512x512 strip LUT PNG")
    parser.add_argument("--trim-bin", type=Path, default=None, help="Optional output path for trimmed libm9base bin")
    args = parser.parse_args()

    src = args.preview_bin.read_bytes()
    lut17 = np.frombuffer(src[HEADER_SIZE : HEADER_SIZE + LUT17_BYTES], dtype=np.uint8).reshape(17, 17, 17, 3).astype(np.float32)
    lut64 = upsample_17_to_64(lut17)
    md5 = bake_512_png(lut64, args.out_png)
    print(f"wrote {args.out_png} (512x512, md5={md5})")

    if args.trim_bin is not None:
        sha = trim_filter_bin(src, args.trim_bin)
        print(f"wrote {args.trim_bin} ({args.trim_bin.stat().st_size} bytes, sha256={sha[:16]}...)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
